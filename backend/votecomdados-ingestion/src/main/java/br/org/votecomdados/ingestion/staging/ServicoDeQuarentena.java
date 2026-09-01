package br.org.votecomdados.ingestion.staging;

import br.org.votecomdados.core.dominio.Enums.Fonte;
import br.org.votecomdados.core.dominio.Enums.MotivoRejeicao;
import br.org.votecomdados.ingestion.execucao.Execucao;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * O oposto de descartar em silêncio.
 *
 * <p>Um voto cujo parlamentar não foi vinculado viola a FK de
 * {@code voto_nominal}. Tratar isso com {@code ON CONFLICT DO NOTHING} ou
 * try/catch por registro faria o voto <b>desaparecer sem rastro</b> — e numa
 * plataforma de transparência, omitir voto em silêncio é pior que falhar alto:
 * o erro é indetectável para quem consulta.
 *
 * <p>Aqui o registro vira item de trabalho visível, com payload para reprocesso.
 */
@Service
public class ServicoDeQuarentena {

    private static final Logger log = LoggerFactory.getLogger(ServicoDeQuarentena.class);

    private final JdbcClient jdbc;
    private final RedatorDeCamposSensiveis redator;
    private final ObjectMapper json;

    ServicoDeQuarentena(JdbcClient jdbc, RedatorDeCamposSensiveis redator, ObjectMapper json) {
        this.jdbc = jdbc;
        this.redator = redator;
        this.json = json;
    }

    /**
     * Põe um registro em quarentena. Devolve {@code true} se o caso era novo.
     *
     * <p>O payload passa pela <b>mesma</b> allowlist do staging: quarentena não
     * é exceção à redação. Seria o vazamento mais fácil de esquecer, porque só
     * acontece no caminho de erro.
     *
     * <p>Reprocessar não multiplica linhas — o índice único parcial cobre o
     * caso aberto. Sem isso, {@code FORA_DA_COORTE}, que é gravado uma vez por
     * parlamentar, viraria uma linha por parlamentar <i>por execução</i>.
     */
    public boolean rejeitar(Execucao execucao, String recurso, String idExterno,
                            MotivoRejeicao motivo, String detalhe, JsonNode payloadDaFonte) {
        PayloadRedigido redigido = redator.redigir(execucao.fonte(), recurso, payloadDaFonte);

        int inseridos = jdbc.sql("""
                INSERT INTO staging.registro_rejeitado
                    (execucao_id, fonte, recurso, id_externo, motivo, detalhe, payload)
                VALUES (:execucao, :fonte::fonte_enum, :recurso, :idExterno,
                        :motivo::motivo_rejeicao_enum, :detalhe, :payload::jsonb)
                ON CONFLICT (fonte, recurso, id_externo, motivo)
                    WHERE resolvido_em IS NULL
                    DO NOTHING
                """)
            .param("execucao", execucao.id())
            .param("fonte", execucao.fonte().name())
            .param("recurso", recurso)
            .param("idExterno", idExterno)
            .param("motivo", motivo.name())
            .param("detalhe", detalhe)
            .param("payload", json.writeValueAsString(redigido.payload()))
            .update();

        if (inseridos == 1 && motivo.exigeAlerta()) {
            log.warn("quarentena: {} {} da fonte {} — {}", recurso, idExterno,
                     execucao.fonte(), motivo);
        }
        return inseridos == 1;
    }

    /**
     * Métrica de negócio: o que está em quarentena e ainda não foi resolvido.
     *
     * <p>{@code FORA_DA_COORTE} fica de fora por construção — é o caso
     * esperado, e incluí-lo faria o alerta nascer com dezenas de milhares de
     * linhas no primeiro dia, tornando-o inútil.
     */
    public List<ContagemDeQuarentena> pendentesQueAlertam() {
        return jdbc.sql("""
                SELECT fonte::text AS fonte, motivo::text AS motivo, count(*) AS total
                  FROM staging.registro_rejeitado
                 WHERE resolvido_em IS NULL AND motivo <> 'FORA_DA_COORTE'
                 GROUP BY 1, 2
                 ORDER BY 3 DESC
                """)
            .query((rs, n) -> new ContagemDeQuarentena(
                Fonte.valueOf(rs.getString("fonte")),
                MotivoRejeicao.valueOf(rs.getString("motivo")),
                rs.getLong("total")))
            .list();
    }

    /** Quantos ficaram de fora por não serem da coorte — contado, nunca alertado. */
    public long foraDaCoorte(Fonte fonte) {
        return jdbc.sql("""
                SELECT count(*) FROM staging.registro_rejeitado
                 WHERE fonte = :fonte::fonte_enum AND motivo = 'FORA_DA_COORTE'
                   AND resolvido_em IS NULL
                """).param("fonte", fonte.name()).query(Long.class).single();
    }

    public record ContagemDeQuarentena(Fonte fonte, MotivoRejeicao motivo, long total) {}
}
