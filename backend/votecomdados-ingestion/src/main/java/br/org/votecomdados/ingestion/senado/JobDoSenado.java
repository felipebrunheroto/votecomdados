package br.org.votecomdados.ingestion.senado;

import br.org.votecomdados.core.dominio.Enums.MotivoRejeicao;
import br.org.votecomdados.ingestion.execucao.Execucao;
import br.org.votecomdados.ingestion.staging.RepositorioDePayloadBruto;
import br.org.votecomdados.ingestion.staging.ServicoDeQuarentena;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Carga de votações do Senado.
 *
 * <h2>O Senado é o oposto da Câmara, e isso simplifica</h2>
 *
 * A Casa publica <b>a bancada inteira em cada votação</b> — 81 registros, com
 * licença, missão e ausência declaradas por ela. Nada aqui é derivado: o
 * {@code DerivadorDeAusencia} recusa o Senado de propósito, porque derivar
 * duplicaria o que a fonte já afirma.
 *
 * <h2>Metade das votações é secreta</h2>
 *
 * Em 53% das votações de plenário a deliberação é secreta: a Casa registra
 * <b>quem participou</b>, não como votou. Isso chega como o rótulo
 * {@code Votou}, que vira {@code SECRETO} — e é o rótulo mais frequente do
 * Senado. Verificado em 22 mil linhas: {@code Votou} aparece exclusivamente
 * quando {@code votacaoSecreta = 'S'}, e {@code Sim}/{@code Não} só quando é
 * {@code 'N'}. A flag e o rótulo nunca se contradizem.
 */
@Component
public class JobDoSenado {

    private static final Logger log = LoggerFactory.getLogger(JobDoSenado.class);

    private final JdbcClient jdbc;
    private final RepositorioDePayloadBruto staging;
    private final ServicoDeQuarentena quarentena;
    private final ObjectMapper json;

    JobDoSenado(JdbcClient jdbc, RepositorioDePayloadBruto staging,
                ServicoDeQuarentena quarentena, ObjectMapper json) {
        this.jdbc = jdbc;
        this.staging = staging;
        this.quarentena = quarentena;
        this.json = json;
    }

    public Resultado carregar(Execucao execucao, Iterable<JsonNode> votacoes) {
        int gravadas = 0, votos = 0;
        var rotulosSemTraducao = new HashSet<String>();

        for (JsonNode v : votacoes) {
            String idExterno = texto(v, "codigoSessaoVotacao");
            if (idExterno == null) {
                quarentena.rejeitar(execucao, "votacao", null,
                                    MotivoRejeicao.PAYLOAD_INVALIDO,
                                    "votacao sem codigoSessaoVotacao", v);
                continue;
            }
            staging.gravar(execucao, "votacao", idExterno, v);

            long votacaoId = gravarVotacao(v, idExterno);
            gravadas++;
            votos += gravarVotos(execucao, v, votacaoId, rotulosSemTraducao);
        }

        for (String rotulo : rotulosSemTraducao) {
            quarentena.rejeitar(execucao, "voto", rotulo,
                                MotivoRejeicao.VALOR_VOTO_NAO_MAPEADO,
                                "rotulo do Senado sem traducao em mapeamento_voto",
                                json.createObjectNode().put("siglaVotoParlamentar", rotulo));
        }
        if (!rotulosSemTraducao.isEmpty()) {
            log.warn("{} rotulo(s) do Senado sem traducao: {}",
                     rotulosSemTraducao.size(), rotulosSemTraducao);
        }
        return new Resultado(gravadas, votos, List.copyOf(rotulosSemTraducao));
    }

    /**
     * Toda votação do serviço é nominal — ele existe para publicar voto
     * individual. Secreta continua nominal: há registro de quem participou,
     * só não de como votou.
     */
    private long gravarVotacao(JsonNode v, String idExterno) {
        boolean secreta = "S".equalsIgnoreCase(texto(v, "votacaoSecreta"));
        return jdbc.sql("""
                INSERT INTO votacao (casa, id_externo, data_votacao, descricao, tipo,
                                     ambito, secreta, aprovada, url_fonte)
                VALUES ('SENADO', :id,
                        :data::date AT TIME ZONE 'America/Sao_Paulo',
                        :descricao, 'NOMINAL', 'PLENARIO', :secreta, :aprovada,
                        'https://legis.senado.leg.br/dadosabertos/votacao?codigoSessao='
                            || coalesce(:sessao, ''))
                ON CONFLICT (casa, id_externo) DO UPDATE SET
                    descricao = EXCLUDED.descricao,
                    secreta = EXCLUDED.secreta,
                    aprovada = EXCLUDED.aprovada
                RETURNING id
                """)
            .param("id", idExterno)
            .param("data", texto(v, "dataSessao"))
            .param("descricao", coalesce(texto(v, "descricaoVotacao"),
                                         texto(v, "identificacao"),
                                         "Votação nominal"))
            .param("secreta", secreta)
            .param("aprovada", aprovada(texto(v, "resultadoVotacao")))
            .param("sessao", texto(v, "codigoSessao"))
            .query(Long.class).single();
    }

    /**
     * Só entram os votos de quem é da coorte. Os demais já foram contados uma
     * vez, no cadastro — registrá-los por voto encheria a métrica de alerta com
     * 81 linhas por votação.
     */
    private int gravarVotos(Execucao execucao, JsonNode votacao, long votacaoId,
                            Set<String> semTraducao) {
        JsonNode votos = votacao.get("votos");
        if (votos == null || !votos.isArray()) return 0;

        int gravados = 0;
        for (JsonNode voto : votos) {
            String rotulo = texto(voto, "siglaVotoParlamentar");
            String codigo = texto(voto, "codigoParlamentar");
            if (rotulo == null || codigo == null) continue;

            UUID politico = politicoDe(codigo);
            if (politico == null) continue;   // fora da coorte

            String normalizado = traduzir(rotulo);
            if (normalizado == null) {
                // 'NA' ("Dispositivo não citado") cai aqui de propósito: não é
                // voto, e não tem tradução honesta. Preferimos não classificar.
                semTraducao.add(rotulo);
                continue;
            }

            jdbc.sql("""
                    INSERT INTO voto_nominal (votacao_id, politico_id, voto, voto_origem,
                                              origem_registro)
                    VALUES (:votacao, :politico, :voto::tipo_voto_enum, :origem, 'FONTE')
                    ON CONFLICT (votacao_id, politico_id) DO UPDATE SET
                        voto = EXCLUDED.voto,
                        voto_origem = EXCLUDED.voto_origem,
                        origem_registro = EXCLUDED.origem_registro
                    """)
                .param("votacao", votacaoId)
                .param("politico", politico)
                .param("voto", normalizado)
                .param("origem", rotulo)
                .update();
            gravados++;
        }
        return gravados;
    }

    private String traduzir(String rotulo) {
        return jdbc.sql("""
                SELECT voto::text FROM mapeamento_voto
                 WHERE fonte = 'SENADO' AND valor_origem = :rotulo
                """).param("rotulo", rotulo).query(String.class).optional().orElse(null);
    }

    private UUID politicoDe(String codigoParlamentar) {
        return jdbc.sql("""
                SELECT politico_id FROM identificador_externo
                 WHERE sistema = 'SENADO' AND identificador = :id
                """).param("id", codigoParlamentar).query(UUID.class).optional().orElse(null);
    }

    /** 'A' aprovada, 'R' rejeitada; qualquer outra coisa é desconhecida. */
    private static Boolean aprovada(String resultado) {
        if (resultado == null) return null;
        return switch (resultado.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "A", "APROVADA", "APROVADO" -> Boolean.TRUE;
            case "R", "REJEITADA", "REJEITADO" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static String coalesce(String... valores) {
        for (String v : valores) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static String texto(JsonNode no, String campo) {
        JsonNode v = no.get(campo);
        if (v == null || v.isNull()) return null;
        String s = v.asString().trim();
        return s.isEmpty() ? null : s;
    }

    public record Resultado(int votacoes, int votos, List<String> rotulosSemTraducao) {}
}
