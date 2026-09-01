package br.org.votecomdados.ingestion.staging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.org.votecomdados.core.dominio.Enums.Fonte;
import br.org.votecomdados.core.dominio.Enums.MotivoRejeicao;
import br.org.votecomdados.core.dominio.Enums.TipoJob;
import br.org.votecomdados.ingestion.execucao.ControleDeExecucaoService;
import br.org.votecomdados.ingestion.execucao.Execucao;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * O B1 contra um banco real: o CPF não pode chegar ao disco.
 *
 * <p>O teste central usa um payload do TSE <b>com</b> {@code NR_CPF_CANDIDATO} e
 * verifica no Postgres que ele não está lá — não basta a classe dizer que
 * redige, é preciso ver o que ficou gravado.
 */
@SpringBootTest
@Testcontainers
class RedacaoDeStagingTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("votecomdados");

    @DynamicPropertySource
    static void configurar(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registro.add("spring.datasource.username", POSTGRES::getUsername);
        registro.add("spring.datasource.password", POSTGRES::getPassword);
        registro.add("spring.flyway.enabled", () -> true);
    }

    private static final String CPF_DE_TESTE = "12345678901";

    /** Uma candidatura do TSE como a fonte entrega: com CPF e título. */
    private static final String CANDIDATURA_TSE = """
        {
          "SQ_CANDIDATO": "250001234567",
          "ANO_ELEICAO": 2026,
          "NM_CANDIDATO": "FULANA DE TESTE SOUZA",
          "NM_URNA_CANDIDATO": "FULANA TESTE",
          "NR_CPF_CANDIDATO": "%s",
          "NR_TITULO_ELEITORAL_CANDIDATO": "123456789012",
          "DS_CARGO": "DEPUTADO FEDERAL",
          "SG_UF": "SP",
          "SG_PARTIDO": "XYZ",
          "DT_NASCIMENTO": "1975-04-12",
          "NM_EMAIL": "fulana@exemplo.org",
          "CAMPO_NOVO_QUE_A_FONTE_INVENTOU": "qualquer coisa"
        }
        """.formatted(CPF_DE_TESTE);

    @Autowired ControleDeExecucaoService controle;
    @Autowired RedatorDeCamposSensiveis redator;
    @Autowired RepositorioDePayloadBruto staging;
    @Autowired ServicoDeQuarentena quarentena;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    Execucao execucao;

    @BeforeEach
    void abrirExecucao() {
        execucao = controle.iniciar(Fonte.TSE, TipoJob.COORTE, "{}");
    }

    @AfterEach
    void limpar() {
        controle.close();
        jdbc.sql("DELETE FROM staging.payload_bruto").update();
        jdbc.sql("DELETE FROM staging.registro_rejeitado").update();
        jdbc.sql("DELETE FROM ingestao_execucao").update();
    }

    @Test
    void cpf_nao_chega_ao_banco() {
        staging.gravar(execucao, "candidatura", "250001234567", candidatura());

        String gravado = jdbc.sql("SELECT payload::text FROM staging.payload_bruto")
            .query(String.class).single();

        assertThat(gravado)
            .as("o payload gravado nao pode conter o CPF nem a chave dele")
            .doesNotContain(CPF_DE_TESTE)
            .doesNotContain("NR_CPF_CANDIDATO")
            .doesNotContain("NR_TITULO_ELEITORAL_CANDIDATO");
        assertThat(gravado).contains("FULANA TESTE");
    }

    @Test
    void a_redacao_deixa_rastro_do_que_removeu() {
        staging.gravar(execucao, "candidatura", "250001234567", candidatura());

        var redigidos = jdbc.sql("""
                SELECT array_to_string(campos_redigidos, ',') FROM staging.payload_bruto
                """).query(String.class).single();

        assertThat(redigidos)
            .as("sem esse registro, 'o CPF nao esta gravado' seria afirmacao sem prova")
            .contains("NR_CPF_CANDIDATO")
            .contains("NR_TITULO_ELEITORAL_CANDIDATO")
            .contains("NM_EMAIL");
    }

    /**
     * A razão de ser da allowlist: fonte de governo acrescenta campo sem avisar,
     * e o campo novo precisa entrar como ignorado, não como vazamento.
     */
    @Test
    void campo_novo_e_desconhecido_e_descartado_por_omissao() {
        var redigido = redator.redigir(Fonte.TSE, "candidatura", candidatura());

        assertThat(redigido.payload().has("CAMPO_NOVO_QUE_A_FONTE_INVENTOU")).isFalse();
        assertThat(redigido.camposRedigidos()).contains("CAMPO_NOVO_QUE_A_FONTE_INVENTOU");
    }

    @Test
    void origem_sem_allowlist_declarada_falha_em_vez_de_gravar() {
        assertThatThrownBy(() ->
                redator.redigir(Fonte.TSE, "recurso_nunca_revisado", candidatura()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sem allowlist declarada");
    }

    /** O Senado aninha a bancada inteira; o filho tem allowlist própria. */
    @Test
    void array_aninhado_tambem_passa_pela_allowlist() {
        var votacao = json.readTree("""
            {
              "codigoSessao": 450520,
              "dataSessao": "2025-02-19",
              "votacaoSecreta": "N",
              "campoNaoDeclarado": "descartar",
              "votos": [
                {"codigoParlamentar": 5672, "nomeParlamentar": "Alan Rick",
                 "siglaVotoParlamentar": "Sim", "cpfDoParlamentar": "%s"}
              ]
            }
            """.formatted(CPF_DE_TESTE));

        var redigido = redator.redigir(Fonte.SENADO, "votacao", votacao);
        String texto = json.writeValueAsString(redigido.payload());

        assertThat(texto).doesNotContain(CPF_DE_TESTE).doesNotContain("cpfDoParlamentar");
        assertThat(texto).contains("Alan Rick");
        assertThat(redigido.camposRedigidos()).contains("cpfDoParlamentar", "campoNaoDeclarado");
    }

    @Test
    void payload_identico_nao_gera_linha_nova() {
        assertThat(staging.gravar(execucao, "candidatura", "250001234567", candidatura()))
            .as("primeira coleta").isTrue();
        assertThat(staging.gravar(execucao, "candidatura", "250001234567", candidatura()))
            .as("recoletar o mesmo dado nao pode engordar o staging").isFalse();

        assertThat(staging.contarDaExecucao(execucao)).isEqualTo(1);
    }

    /**
     * O A4: com a semântica padrão do Postgres, NULL != NULL, e todo registro
     * sem id_externo escaparia da deduplicação em silêncio.
     */
    @Test
    void dedup_funciona_mesmo_sem_id_externo() {
        assertThat(staging.gravar(execucao, "candidatura", null, candidatura())).isTrue();
        assertThat(staging.gravar(execucao, "candidatura", null, candidatura())).isFalse();
    }

    @Test
    void quarentena_guarda_o_registro_redigido_e_nao_o_cru() {
        quarentena.rejeitar(execucao, "candidatura", "250001234567",
                            MotivoRejeicao.PAYLOAD_INVALIDO, "sem data de nascimento",
                            candidatura());

        String gravado = jdbc.sql("SELECT payload::text FROM staging.registro_rejeitado")
            .query(String.class).single();

        assertThat(gravado)
            .as("quarentena e caminho de erro — e caminho de erro tambem vaza")
            .doesNotContain(CPF_DE_TESTE);
    }

    @Test
    void reprocessar_nao_multiplica_o_mesmo_caso_aberto() {
        assertThat(quarentena.rejeitar(execucao, "candidatura", "250001234567",
                   MotivoRejeicao.FORA_DA_COORTE, null, candidatura())).isTrue();
        assertThat(quarentena.rejeitar(execucao, "candidatura", "250001234567",
                   MotivoRejeicao.FORA_DA_COORTE, null, candidatura())).isFalse();

        assertThat(quarentena.foraDaCoorte(Fonte.TSE)).isEqualTo(1);
    }

    /**
     * A distinção que faz o alerta valer alguma coisa: quem não é da coorte é
     * esperado, e não pode empurrar a métrica para longe de zero.
     */
    @Test
    void fora_da_coorte_conta_mas_nao_alerta() {
        quarentena.rejeitar(execucao, "candidatura", "aaa",
                            MotivoRejeicao.FORA_DA_COORTE, null, candidatura());
        quarentena.rejeitar(execucao, "candidatura", "bbb",
                            MotivoRejeicao.PAYLOAD_INVALIDO, "ementa vazia", candidatura());

        var alertam = quarentena.pendentesQueAlertam();

        assertThat(alertam).hasSize(1);
        assertThat(alertam.getFirst().motivo()).isEqualTo(MotivoRejeicao.PAYLOAD_INVALIDO);
        assertThat(quarentena.foraDaCoorte(Fonte.TSE)).isEqualTo(1);
    }

    @Test
    void mascara_de_log_cobre_o_caminho_de_erro() {
        String linha = "falha ao normalizar: {\"NR_CPF_CANDIDATO\": \"123.456.789-01\","
                       + " \"titulo\": \"123456789012\"}";

        String mascarada = MascaraDeDadosSensiveis.mascarar(linha);

        assertThat(mascarada).doesNotContain("123.456.789-01").doesNotContain("123456789012");
        assertThat(mascarada).contains("«cpf-mascarado»").contains("«titulo-mascarado»");
    }

    /**
     * A máscara só vale se estiver ligada no appender de verdade. Testar o
     * método provaria que a regex funciona; isto prova que ela está no caminho
     * por onde o log realmente sai — que é o que importa quando alguém logar um
     * payload cru às três da manhã.
     */
    @Test
    void a_mascara_esta_ligada_no_appender_e_nao_so_na_classe() {
        var capturado = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(capturado, true, StandardCharsets.UTF_8));
            org.slf4j.LoggerFactory.getLogger(RedacaoDeStagingTest.class)
                .error("payload recusado pela fonte: {\"NR_CPF_CANDIDATO\": \"{}\"}",
                       CPF_DE_TESTE);
        } finally {
            System.setOut(original);
        }

        String saida = capturado.toString(StandardCharsets.UTF_8);
        assertThat(saida).as("o log precisa ter saido, senao o teste nao prova nada")
            .contains("payload recusado pela fonte");
        assertThat(saida).doesNotContain(CPF_DE_TESTE);
        assertThat(saida).contains("«cpf-mascarado»");
    }

    private JsonNode candidatura() {
        return json.readTree(CANDIDATURA_TSE);
    }
}
