package br.org.votecomdados.ingestion.derivacao;

import br.org.votecomdados.core.dominio.Enums.CasaLegislativa;
import br.org.votecomdados.core.dominio.Enums.Fonte;
import br.org.votecomdados.ingestion.execucao.Execucao;
import java.util.EnumSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Calcula quem faltou. É o passo que fecha o B8.
 *
 * <h2>Por que isto precisa existir</h2>
 *
 * Nenhuma fonte da Câmara publica "faltou". O arquivo {@code votacoesVotos}
 * lista <b>apenas quem registrou voto</b> — em 2026 são cinco rótulos, nenhum
 * de ausência, e a mediana é de 398 linhas para 513 cadeiras. Sem derivação, a
 * plataforma mostraria só quem votou, e um parlamentar que faltou a 40% das
 * votações apareceria com histórico aparentemente limpo. Omitir ausência em
 * silêncio é pior que não ter o dado: é enganoso, e indetectável para quem lê.
 *
 * <h2>Ausência e licença não são a mesma coisa</h2>
 *
 * O universo do dia vem de {@code mandato_exercicio}. Quem estava em
 * {@code LICENCA} vira {@code LICENCIADO}, não {@code AUSENTE} — exibir licença
 * como falta atribuiria à pessoa uma escolha que ela não fez. Quem não estava
 * na Casa naquele dia (suplente não convocado, mandato encerrado) <b>não gera
 * linha nenhuma</b>: não é ausência, é não ser parlamentar.
 *
 * <h2>Nem toda Casa precisa disto</h2>
 *
 * O Senado publica a bancada inteira em cada votação, com licença e ausência
 * declaradas pela própria Casa. Derivar lá duplicaria o que já é fato — por
 * isso o método recusa Casas que publicam o universo, em vez de silenciosamente
 * não fazer nada.
 */
@Component
public class DerivadorDeAusencia {

    private static final Logger log = LoggerFactory.getLogger(DerivadorDeAusencia.class);

    /**
     * Casas cuja fonte já publica o universo da votação.
     *
     * <p>Verificado em 31/08/2026: o serviço de votações do Senado devolve os
     * 81 parlamentares em cada votação, com `NCom`, `LS`, `AP` e afins.
     */
    private static final Set<CasaLegislativa> PUBLICAM_O_UNIVERSO =
        EnumSet.of(CasaLegislativa.SENADO);

    private final JdbcClient jdbc;

    DerivadorDeAusencia(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Resultado derivar(Execucao execucao, CasaLegislativa casa) {
        if (PUBLICAM_O_UNIVERSO.contains(casa)) {
            throw new IllegalArgumentException(
                casa + " publica a bancada inteira em cada votação; derivar aqui "
                + "duplicaria o que a Casa já declara como fato");
        }

        // Recalcular do zero, não completar o que existe. Um período de
        // exercício corrigido retroativamente pela fonte pode ter tirado alguém
        // do universo — e a linha derivada antiga continuaria dizendo que essa
        // pessoa faltou a uma sessão de que nunca participou.
        int removidas = jdbc.sql("""
                DELETE FROM voto_nominal vn
                 USING votacao v
                 WHERE vn.votacao_id = v.id
                   AND v.casa = :casa::casa_legislativa_enum
                   AND vn.origem_registro = 'DERIVADO'
                """).param("casa", casa.name()).update();

        int criadas = jdbc.sql("""
                INSERT INTO voto_nominal (votacao_id, politico_id, voto, voto_origem,
                                          origem_registro)
                SELECT v.id, m.politico_id,
                       CASE m.situacao
                           WHEN 'LICENCA' THEN 'LICENCIADO'
                           ELSE 'AUSENTE'
                       END::tipo_voto_enum,
                       NULL,
                       'DERIVADO'
                  FROM votacao v
                  JOIN mandato_exercicio m
                    ON m.casa = v.casa
                   -- A data é a LOCAL da sessão. Usar a data UTC deslocaria
                   -- votações noturnas em um dia, e a pessoa apareceria
                   -- ausente de uma sessão que ocorreu enquanto ela estava em
                   -- exercício (ou o contrário).
                   AND daterange(m.inicio, m.fim, '[)')
                       @> (v.data_votacao AT TIME ZONE 'America/Sao_Paulo')::date
                 WHERE v.casa = :casa::casa_legislativa_enum
                   -- Votação simbólica não tem registro individual algum: nem
                   -- da fonte, nem derivado.
                   AND v.tipo = 'NOMINAL'
                   AND EXISTS (SELECT 1 FROM mapeamento_situacao ms
                                WHERE ms.fonte = :fonte::fonte_enum
                                  AND ms.situacao = m.situacao
                                  AND ms.conta_no_universo)
                   AND NOT EXISTS (SELECT 1 FROM voto_nominal vn
                                    WHERE vn.votacao_id = v.id
                                      AND vn.politico_id = m.politico_id)
                ON CONFLICT (votacao_id, politico_id) DO NOTHING
                """)
            .param("casa", casa.name())
            .param("fonte", fonteDe(casa).name())
            .update();

        var contagem = jdbc.sql("""
                SELECT count(*) FILTER (WHERE vn.voto = 'AUSENTE')    AS ausentes,
                       count(*) FILTER (WHERE vn.voto = 'LICENCIADO') AS licenciados
                  FROM voto_nominal vn
                  JOIN votacao v ON v.id = vn.votacao_id
                 WHERE v.casa = :casa::casa_legislativa_enum
                   AND vn.origem_registro = 'DERIVADO'
                """)
            .param("casa", casa.name())
            .query((rs, n) -> new Resultado(rs.getInt("ausentes"),
                                            rs.getInt("licenciados"), removidas))
            .single();

        log.info("derivacao em {}: {} ausencias e {} licencas calculadas "
                 + "({} linhas antigas substituidas)",
                 casa, contagem.ausentes(), contagem.licenciados(), removidas);
        return contagem;
    }

    private static Fonte fonteDe(CasaLegislativa casa) {
        return switch (casa) {
            case CAMARA -> Fonte.CAMARA;
            case SENADO -> Fonte.SENADO;
            case ALESP -> Fonte.ALESP;
        };
    }

    /**
     * @param removidas linhas derivadas apagadas antes do recálculo — número
     *                  alto e inesperado indica mudança de mandato na origem
     */
    public record Resultado(int ausentes, int licenciados, int removidas) {}
}
