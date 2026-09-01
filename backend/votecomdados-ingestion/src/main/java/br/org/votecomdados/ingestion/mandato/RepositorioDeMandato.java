package br.org.votecomdados.ingestion.mandato;

import br.org.votecomdados.core.dominio.Enums.CasaLegislativa;
import br.org.votecomdados.core.dominio.Enums.Fonte;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Escrita de {@code mandato_exercicio} — a tabela que torna possível dizer que
 * alguém faltou.
 */
@Repository
public class RepositorioDeMandato {

    private final JdbcClient jdbc;

    RepositorioDeMandato(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Tradução rótulo da Casa → enum, lida do banco.
     *
     * <p>Vem de {@code mapeamento_situacao}, e não de um {@code switch} em
     * código, pela mesma razão do mapeamento de voto: é decisão editorial, tem
     * de ser revisável em produção por quem entende de processo legislativo, e
     * precisa viajar nos dados abertos para que o cruzamento seja auditável.
     */
    public Map<String, String> mapeamentoDe(Fonte fonte) {
        return jdbc.sql("""
                SELECT valor_origem, situacao::text AS situacao
                  FROM mapeamento_situacao WHERE fonte = :fonte::fonte_enum
                """)
            .param("fonte", fonte.name())
            .query((rs, n) -> Map.entry(rs.getString("valor_origem"), rs.getString("situacao")))
            .list().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Substitui os períodos da pessoa naquela Casa.
     *
     * <p>Apaga e regrava em vez de fazer upsert por período. A fonte é a
     * verdade e o histórico dela pode ser <b>corrigido retroativamente</b> —
     * um período que deixou de existir precisa sumir daqui também. Upsert
     * deixaria o período antigo para trás, e ele sobreporia o novo, violando o
     * {@code EXCLUDE} ou, pior, produzindo ausência onde havia licença.
     */
    public int substituirPeriodos(UUID politicoId, CasaLegislativa casa,
                                  Iterable<PeriodoNormalizado> periodos) {
        jdbc.sql("""
                DELETE FROM mandato_exercicio
                 WHERE politico_id = :politico AND casa = :casa::casa_legislativa_enum
                """)
            .param("politico", politicoId).param("casa", casa.name()).update();

        int gravados = 0;
        for (PeriodoNormalizado p : periodos) {
            jdbc.sql("""
                    INSERT INTO mandato_exercicio
                        (politico_id, casa, situacao, situacao_origem, condicao,
                         inicio, fim, url_fonte)
                    VALUES (:politico, :casa::casa_legislativa_enum,
                            :situacao::situacao_exercicio_enum, :situacaoOrigem,
                            :condicao::condicao_eleitoral_enum, :inicio, :fim, :url)
                    """)
                .param("politico", politicoId)
                .param("casa", casa.name())
                .param("situacao", p.situacao())
                .param("situacaoOrigem", p.situacaoOrigem())
                .param("condicao", p.condicao())
                .param("inicio", p.inicio())
                .param("fim", p.fim())
                .param("url", p.urlFonte())
                .update();
            gravados++;
        }
        return gravados;
    }

    /** Quem estava na Casa numa data — o universo de uma votação. */
    public Optional<String> situacaoNaData(UUID politicoId, CasaLegislativa casa,
                                           java.time.LocalDate data) {
        return jdbc.sql("""
                SELECT situacao::text FROM mandato_exercicio
                 WHERE politico_id = :politico
                   AND casa = :casa::casa_legislativa_enum
                   AND daterange(inicio, fim, '[)') @> :data::date
                """)
            .param("politico", politicoId).param("casa", casa.name()).param("data", data)
            .query(String.class).optional();
    }

    /** Período já normalizado, pronto para o banco. */
    public record PeriodoNormalizado(
        String situacao,
        String situacaoOrigem,
        String condicao,
        java.time.LocalDate inicio,
        java.time.LocalDate fim,
        String urlFonte
    ) {}
}
