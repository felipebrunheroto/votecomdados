package br.org.votecomdados.ingestion.coorte;

import static org.assertj.core.api.Assertions.assertThat;

import br.org.votecomdados.core.dominio.Enums.Fonte;
import br.org.votecomdados.core.dominio.Enums.TipoJob;
import br.org.votecomdados.ingestion.execucao.ControleDeExecucaoService;
import br.org.votecomdados.ingestion.execucao.Execucao;
import java.util.List;
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
 * A coorte de ponta a ponta: costura da trajetória, poda e expurgo.
 *
 * <p>Os últimos testes leem o <b>arquivo real</b> do TSE (amostra em
 * {@code db/golden/}, com CPF, título e e-mail substituídos) — é o que prova o
 * layout, e não apenas a lógica de domínio.
 */
@SpringBootTest(properties = "votecomdados.cpf.pepper=pepper-de-teste")
@Testcontainers
class JobDeCoorteTest {

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

    private static final java.nio.file.Path GOLDEN_TSE = java.nio.file.Path.of(
        "..", "..", "db", "golden", "tse-consulta-cand-2026-amostra.csv");

    @Autowired JobDeCoorte job;
    @Autowired LeitorDeArquivoTse leitorDeArquivo;
    @Autowired br.org.votecomdados.ingestion.staging.RedatorDeCamposSensiveis redator;
    @Autowired RepositorioDeCoorte repositorio;
    @Autowired CalculadoraDeHmac hmac;
    @Autowired ControleDeExecucaoService controle;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    Execucao execucao;

    @BeforeEach
    void abrir() {
        execucao = controle.iniciar(Fonte.TSE, TipoJob.COORTE, "{}");
    }

    @AfterEach
    void limpar() {
        controle.close();
        jdbc.sql("DELETE FROM politico").update();
        jdbc.sql("DELETE FROM staging.payload_bruto").update();
        jdbc.sql("DELETE FROM staging.registro_rejeitado").update();
        jdbc.sql("DELETE FROM ingestao_execucao").update();
    }

    /**
     * O que a coorte existe para fazer: três candidaturas em anos diferentes,
     * mesmo CPF, uma pessoa só — com a trajetória inteira.
     */
    @Test
    void mesma_pessoa_em_eleicoes_diferentes_vira_uma_trajetoria() {
        job.carregarAno(execucao, linhas(
            candidatura("900000000001", 2016, "13", "SP", "MARIA DA SILVA SOUZA",
                        "MARIA SILVA", "11122233344"),
            candidatura("900000000002", 2022, "6", "SP", "MARIA DA SILVA SOUZA",
                        "MARIA SILVA", "11122233344"),
            candidatura("900000000003", 2026, "3", "SP", "MARIA DA SILVA SOUZA",
                        "MARIA SILVA", "11122233344")));

        assertThat(contar("politico")).as("um CPF, uma pessoa").isEqualTo(1);
        assertThat(contar("candidatura")).isEqualTo(3);

        var esferas = jdbc.sql("SELECT DISTINCT esfera::text FROM candidatura")
            .query(String.class).list();
        assertThat(esferas).containsExactlyInAnyOrder("MUNICIPAL", "FEDERAL", "ESTADUAL");
    }

    @Test
    void o_cpf_nunca_chega_ao_staging() {
        job.carregarAno(execucao, linhas(
            candidatura("900000000003", 2026, "6", "SP", "MARIA DA SILVA SOUZA",
                        "MARIA SILVA", "11122233344")));

        String staged = jdbc.sql("SELECT payload::text FROM staging.payload_bruto")
            .query(String.class).single();
        assertThat(staged).doesNotContain("11122233344").doesNotContain("NR_CPF_CANDIDATO");
    }

    @Test
    void quem_nao_e_candidato_em_2026_e_podado_com_todo_o_historico() {
        job.carregarAno(execucao, linhas(
            candidatura("900000000010", 2022, "6", "SP", "FULANO QUE SAIU",
                        "FULANO", "22233344455"),
            candidatura("900000000011", 2026, "6", "SP", "BELTRANA QUE FICOU",
                        "BELTRANA", "33344455566")));

        assertThat(contar("politico")).isEqualTo(2);

        job.encerrar();

        assertThat(contar("politico")).as("so quem se apresenta ao eleitorado fica")
            .isEqualTo(1);
        assertThat(contar("candidatura")).as("cascata leva a candidatura junto")
            .isEqualTo(1);
        assertThat(jdbc.sql("SELECT nome_civil FROM politico").query(String.class).single())
            .isEqualTo("BELTRANA QUE FICOU");
    }

    @Test
    void o_expurgo_apaga_o_cpf_hmac_ao_fim() {
        job.carregarAno(execucao, linhas(
            candidatura("900000000020", 2026, "6", "SP", "CICLANA DE TESTE",
                        "CICLANA", "44455566677")));

        assertThat(contar("politico WHERE cpf_hmac IS NOT NULL"))
            .as("durante o job o HMAC precisa existir para costurar").isEqualTo(1);

        job.encerrar();

        assertThat(contar("politico WHERE cpf_hmac IS NOT NULL"))
            .as("cumprido o papel, o campo vira risco sem uso").isZero();
    }

    /**
     * O caso que quase passou despercebido: depois do expurgo o HMAC é NULL, e
     * uma resolução de identidade que dependesse só dele criaria uma pessoa
     * nova a cada execução — fragmentando a trajetória em silêncio.
     */
    @Test
    void reexecutar_depois_do_expurgo_nao_duplica_ninguem() {
        var linha = candidatura("900000000030", 2026, "6", "SP", "DELTRANO DE TESTE",
                                "DELTRANO", "55566677788");

        job.carregarAno(execucao, linhas(linha));
        job.encerrar();
        controle.concluir(execucao, null, 1, 0);

        var segunda = controle.iniciar(Fonte.TSE, TipoJob.COORTE, "{}");
        job.carregarAno(segunda, linhas(linha));
        job.encerrar();

        assertThat(contar("politico")).as("sq_candidato_tse ancora a identidade "
            + "entre execucoes, e ele nao e apagado").isEqualTo(1);
        assertThat(contar("candidatura")).isEqualTo(1);
    }

    @Test
    void status_de_registro_e_preservado_inclusive_indeferido() {
        var indeferida = candidatura("900000000040", 2026, "6", "SP", "EX-CANDIDATO",
                                     "EX", "66677788899");
        ((tools.jackson.databind.node.ObjectNode) indeferida)
            .put("DS_SITUACAO_CANDIDATURA", "INDEFERIDO");

        job.carregarAno(execucao, linhas(indeferida));

        assertThat(jdbc.sql("SELECT status::text FROM candidatura").query(String.class).single())
            .as("omitir quem esta em disputa judicial pareceria esconder candidato")
            .isEqualTo("INDEFERIDO");
    }

    @Test
    void eleicao_que_ainda_nao_ocorreu_nao_diz_que_a_pessoa_perdeu() {
        job.carregarAno(execucao, linhas(
            candidatura("900000000050", 2026, "6", "SP", "CANDIDATA DE 2026",
                        "CANDIDATA", "77788899900")));

        var eleito = jdbc.sql("SELECT eleito FROM candidatura").query(Boolean.class).optional();
        assertThat(eleito).as("false diria que perdeu; nulo diz que ainda nao se sabe")
            .isEmpty();
    }

    @Test
    void cargo_desconhecido_vai_para_quarentena_em_vez_de_sumir() {
        var estranha = candidatura("900000000060", 2026, "99", "SP", "CARGO INVENTADO",
                                   "CARGO", "88899900011");

        var r = job.carregarAno(execucao, linhas(estranha));

        assertThat(r.rejeitados()).isEqualTo(1);
        assertThat(contar("staging.registro_rejeitado")).isEqualTo(1);
        assertThat(contar("politico")).isZero();
    }

    /**
     * O HMAC do Java tem de bater com o do pgcrypto: se divergirem, o casamento
     * silenciosamente deixa de funcionar — e nada quebra alto.
     */
    @Test
    void hmac_do_java_bate_com_o_do_postgres() {
        String noJava = hmac.hmacDe("111.222.333-44");
        String noBanco = jdbc.sql("""
                SELECT encode(hmac('11122233344', :pepper, 'sha256'), 'hex')
                """).param("pepper", "pepper-de-teste").query(String.class).single();

        assertThat(noJava).isEqualTo(noBanco).hasSize(64);
    }

    @Test
    void cpf_sem_zeros_a_esquerda_produz_o_mesmo_hmac() {
        assertThat(hmac.hmacDe("1122233344")).isEqualTo(hmac.hmacDe("01122233344"));
    }

    // ------------------------------------------------- contra o arquivo real

    /**
     * O erro mais caro que a verificação do arquivo real pegou.
     *
     * <p>Os sentinelas do TSE são {@code #NE} e {@code #NULO} — sem o {@code #}
     * final que o código presumia. Com a grafia errada, {@code DS_SIT_TOT_TURNO}
     * escapava do filtro e a pessoa era gravada como <b>não eleita</b>, para uma
     * eleição que só ocorre em outubro. Seriam 20.809 candidatos marcados como
     * derrotados antes da votação.
     */
    @Test
    void eleicao_futura_nao_marca_ninguem_como_derrotado_no_arquivo_real() {
        job.carregarAno(execucao, leitorDeArquivo.lerCsv(GOLDEN_TSE).iterator());

        var comEleitoDefinido = jdbc.sql(
            "SELECT count(*) FROM candidatura WHERE eleito IS NOT NULL")
            .query(Long.class).single();

        assertThat(comEleitoDefinido)
            .as("a eleicao de 2026 ainda nao ocorreu")
            .isZero();
    }

    /**
     * O segundo erro: {@code DS_SITUACAO_CANDIDATURA} vem {@code #NE} em 100%
     * das candidaturas de 2026, porque o registro está sendo julgado. Traduzir
     * isso para {@code APTO} seria a plataforma afirmar em nome do TSE.
     */
    @Test
    void situacao_nao_declarada_pela_fonte_nao_vira_apto() {
        job.carregarAno(execucao, leitorDeArquivo.lerCsv(GOLDEN_TSE).iterator());

        var status = jdbc.sql("SELECT DISTINCT status::text FROM candidatura")
            .query(String.class).list();

        assertThat(status).containsExactly("NAO_INFORMADO");
    }

    @Test
    void a_acentuacao_do_arquivo_real_sobrevive_a_leitura() {
        job.carregarAno(execucao, leitorDeArquivo.lerCsv(GOLDEN_TSE).iterator());

        var nomes = jdbc.sql("SELECT nome_civil FROM politico").query(String.class).list();

        assertThat(nomes).isNotEmpty();
        assertThat(nomes)
            .as("caractere de substituicao indicaria decodificacao errada")
            .noneMatch(n -> n.contains("�"));
    }

    @Test
    void todos_os_cargos_do_arquivo_real_sao_reconhecidos() {
        var r = job.carregarAno(execucao, leitorDeArquivo.lerCsv(GOLDEN_TSE).iterator());

        assertThat(r.rejeitados()).as("cargo desconhecido iria para quarentena").isZero();
        assertThat(jdbc.sql("SELECT count(DISTINCT cargo) FROM candidatura")
            .query(Long.class).single()).isGreaterThan(5);
    }

    /** A redação funcionando sobre o arquivo de verdade, não sobre fixture. */
    @Test
    void nenhum_identificador_pessoal_do_arquivo_real_chega_ao_staging() {
        job.carregarAno(execucao, leitorDeArquivo.lerCsv(GOLDEN_TSE).iterator());

        var staged = jdbc.sql(
            "SELECT string_agg(payload::text, ' ') FROM staging.payload_bruto")
            .query(String.class).single();

        assertThat(staged)
            .doesNotContain("NR_CPF_CANDIDATO")
            .doesNotContain("NR_TITULO_ELEITORAL_CANDIDATO")
            .doesNotContain("DS_EMAIL")
            .doesNotContain("11111111100");
    }

    /**
     * Allowlist não pode citar coluna que a fonte não tem: um campo fantasma dá
     * a impressão de estar sendo tratado, e nunca é. A primeira versão desta
     * lista tinha dois.
     */
    @Test
    void a_allowlist_do_tse_so_cita_colunas_que_existem() throws Exception {
        var cabecalho = java.nio.file.Files.readAllLines(GOLDEN_TSE,
            java.nio.charset.StandardCharsets.UTF_8).getFirst().replace("﻿", "");
        var colunasDoArquivo = java.util.Arrays.stream(cabecalho.split(";"))
            .map(c -> c.replace("\"", "").trim())
            .collect(java.util.stream.Collectors.toSet());

        var redigido = redator.redigir(Fonte.TSE, "candidatura",
            leitorDeArquivo.lerCsv(GOLDEN_TSE).getFirst());
        var usados = new java.util.ArrayList<String>(redigido.payload().propertyNames());

        assertThat(usados).isNotEmpty();
        assertThat(colunasDoArquivo).containsAll(usados);
    }

    // ---------------------------------------------------------------- auxiliares

    private long contar(String tabela) {
        return jdbc.sql("SELECT count(*) FROM " + tabela).query(Long.class).single();
    }

    private java.util.Iterator<JsonNode> linhas(JsonNode... nos) {
        return List.of(nos).iterator();
    }

    private JsonNode candidatura(String sq, int ano, String cdCargo, String uf,
                                 String nome, String urna, String cpf) {
        return json.readTree("""
            {
              "SQ_CANDIDATO": "%s",
              "ANO_ELEICAO": "%d",
              "NR_TURNO": "1",
              "CD_CARGO": "%s",
              "SG_UF": "%s",
              "SG_UE": "%s",
              "NM_UE": "Campinas",
              "NM_CANDIDATO": "%s",
              "NM_URNA_CANDIDATO": "%s",
              "NR_CPF_CANDIDATO": "%s",
              "DT_NASCIMENTO": "12/04/1975",
              "DS_GENERO": "FEMININO",
              "SG_PARTIDO": "XYZ",
              "NR_PARTIDO": "99",
              "NR_CANDIDATO": "9999",
              "DS_SITUACAO_CANDIDATURA": "DEFERIDO",
              "DS_SIT_TOT_TURNO": "#NULO#"
            }
            """.formatted(sq, ano, cdCargo, uf, uf, nome, urna, cpf));
    }
}
