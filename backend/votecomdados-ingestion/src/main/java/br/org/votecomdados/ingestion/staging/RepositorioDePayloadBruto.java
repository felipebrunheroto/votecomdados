package br.org.votecomdados.ingestion.staging;

import br.org.votecomdados.core.dominio.Enums.Fonte;
import br.org.votecomdados.ingestion.execucao.Execucao;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Grava o payload de origem em {@code staging.payload_bruto} — sempre redigido.
 *
 * <p>O staging existe porque bug de normalização é descoberto tarde: sem o
 * bruto guardado, reprocessar exigiria rebaixar tudo da fonte, e fontes de
 * governo mudam. Com ele, o reprocesso é uma consulta.
 */
@Repository
public class RepositorioDePayloadBruto {

    private final JdbcClient jdbc;
    private final RedatorDeCamposSensiveis redator;
    private final ObjectMapper json;

    RepositorioDePayloadBruto(JdbcClient jdbc, RedatorDeCamposSensiveis redator,
                              ObjectMapper json) {
        this.jdbc = jdbc;
        this.redator = redator;
        this.json = json;
    }

    /**
     * Redige e grava. Devolve {@code true} se a linha era nova.
     *
     * <p>Recoletar payload idêntico não gera linha: o crescimento do staging
     * fica proporcional às <b>mudanças reais</b> nas fontes, não ao número de
     * execuções. Um incremental diário sobre dado estável não engorda nada.
     */
    public boolean gravar(Execucao execucao, String recurso, String idExterno,
                          JsonNode payloadDaFonte) {
        PayloadRedigido redigido = redator.redigir(execucao.fonte(), recurso, payloadDaFonte);
        String texto = json.writeValueAsString(redigido.payload());

        int inseridos = jdbc.sql("""
                INSERT INTO staging.payload_bruto
                    (execucao_id, fonte, recurso, id_externo, payload,
                     campos_redigidos, payload_hash)
                VALUES (:execucao, :fonte::fonte_enum, :recurso, :idExterno,
                        :payload::jsonb, :redigidos, :hash)
                ON CONFLICT DO NOTHING
                """)
            .param("execucao", execucao.id())
            .param("fonte", execucao.fonte().name())
            .param("recurso", recurso)
            .param("idExterno", idExterno)
            .param("payload", texto)
            .param("redigidos", redigido.camposRedigidos().toArray(String[]::new))
            .param("hash", sha256(texto))
            .update();

        return inseridos == 1;
    }

    /**
     * Hash do payload <b>já redigido</b>, e não do original.
     *
     * <p>É o que faz mudança em campo descartado não gerar linha nova: se o
     * CPF de alguém for corrigido na origem, nada muda para nós — não guardamos
     * esse campo, e fingir que o registro mudou só encheria o staging.
     */
    static String sha256(String texto) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(texto.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM sem SHA-256", e);
        }
    }

    /** Retenção: o bruto serve para reprocessar, não é arquivo permanente. */
    public int limparAnterioresA(int dias) {
        return jdbc.sql("""
                DELETE FROM staging.payload_bruto
                 WHERE coletado_em < now() - make_interval(days => :dias)
                """).param("dias", dias).update();
    }

    public long contarDaExecucao(Execucao execucao) {
        return jdbc.sql("SELECT count(*) FROM staging.payload_bruto WHERE execucao_id = :id")
            .param("id", execucao.id()).query(Long.class).single();
    }

    /** Fontes que não declaram allowlist não podem gravar — ver o redator. */
    public Fonte fonteDe(Execucao execucao) {
        return execucao.fonte();
    }
}
