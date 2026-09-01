package br.org.votecomdados.ingestion.publicacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.org.votecomdados.core.dominio.Enums.Fonte;
import br.org.votecomdados.core.dominio.Enums.TipoJob;
import br.org.votecomdados.ingestion.execucao.ControleDeExecucaoService;
import br.org.votecomdados.ingestion.execucao.Execucao;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * O que a ingestão publica ao terminar: a projeção de leitura e o pacote de
 * dados abertos.
 */
@SpringBootTest
@Testcontainers
class PublicacaoTest {

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

    private static final String CPF_HMAC = "a".repeat(64);

    @Autowired ProjecaoDeLeitura projecao;
    @Autowired ExportadorDeDadosAbertos exportador;
    @Autowired ControleDeExecucaoService controle;
    @Autowired JdbcClient jdbc;

    Execucao execucao;

    @BeforeEach
    void abrir() {
        execucao = controle.iniciar(Fonte.CAMARA, TipoJob.BACKFILL, "{}");
    }

    @AfterEach
    void limpar() {
        controle.close();
        jdbc.sql("DELETE FROM politico").update();
        jdbc.sql("DELETE FROM ingestao_execucao").update();
    }

    @Test
    void a_projecao_cobre_todo_mundo_e_registra_a_execucao() {
        candidato("Maria Da Silva");
        candidato("Joao De Souza");

        long perfis = projecao.reconstruir(execucao);

        assertThat(perfis).isEqualTo(2);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM perfil_leitura WHERE execucao_id = :e
                """).param("e", execucao.id()).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void a_projecao_traz_a_trajetoria_e_a_cobertura_ja_montadas(@TempDir Path dir) {
        var id = candidato("Maria Da Silva");

        projecao.reconstruir(execucao);

        var perfil = jdbc.sql("""
                SELECT trajetoria::text || ' :: ' || cobertura::text
                  FROM perfil_leitura WHERE politico_id = :id
                """).param("id", id).query(String.class).single();

        assertThat(perfil).contains("DEPUTADO_FEDERAL").contains("CAMARA");
    }

    @Test
    void o_pacote_sai_com_todos_os_arquivos_e_a_metodologia(@TempDir Path dir) throws IOException {
        candidato("Maria Da Silva");

        Path destino = exportador.exportar(dir);

        assertThat(destino).exists();
        assertThat(destino.resolve("politico.csv")).exists();
        assertThat(destino.resolve("voto_nominal.csv")).exists();
        assertThat(destino.resolve("cobertura_fonte.csv")).exists();
        assertThat(destino.resolve("manifesto.json")).exists();
        assertThat(destino.resolve("LEIA-ME.md")).exists();
    }

    /** A garantia do B1 valendo também na saída, não só na entrada. */
    @Test
    void o_pacote_nao_leva_cpf_nem_quem_fez_a_curadoria(@TempDir Path dir) throws IOException {
        var id = candidato("Maria Da Silva");
        jdbc.sql("UPDATE politico SET cpf_hmac = :h WHERE id = :id")
            .param("h", CPF_HMAC).param("id", id).update();
        jdbc.sql("""
                INSERT INTO identificador_externo (politico_id, sistema, identificador,
                    metodo_resolucao, score_confianca, revisado_manualmente,
                    revisado_por, revisado_em)
                VALUES (:id, 'CAMARA', '123', 'FUZZY', 0.9, true,
                        'curador@exemplo.org', now())
                """).param("id", id).update();

        Path destino = exportador.exportar(dir);

        for (Path arquivo : Files.list(destino).toList()) {
            String conteudo = Files.readString(arquivo, StandardCharsets.UTF_8);
            assertThat(conteudo).as("%s", arquivo.getFileName())
                .doesNotContain(CPF_HMAC)
                .doesNotContain("curador@exemplo.org");
        }
    }

    /** O cruzamento é a parte que mais pede confiança — e a que mais precisa sair. */
    @Test
    void o_pacote_leva_o_cruzamento_com_metodo_e_score(@TempDir Path dir) throws IOException {
        var id = candidato("Maria Da Silva");
        jdbc.sql("""
                INSERT INTO identificador_externo (politico_id, sistema, identificador,
                    metodo_resolucao, score_confianca)
                VALUES (:id, 'CAMARA', '204554', 'FUZZY', 0.8700)
                """).param("id", id).update();

        Path destino = exportador.exportar(dir);
        String csv = Files.readString(destino.resolve("identificador_externo.csv"),
                                      StandardCharsets.UTF_8);

        assertThat(csv).contains("FUZZY").contains("0.8700").contains("204554");
    }

    /**
     * O número desconfortável precisa sair: sem ele, o pacote seria peça de
     * marketing em vez de instrumento de auditoria.
     */
    @Test
    void a_metodologia_declara_a_fila_de_curadoria_pendente(@TempDir Path dir)
            throws IOException {
        var id = candidato("Maria Da Silva");
        jdbc.sql("""
                INSERT INTO identificador_externo (politico_id, sistema, identificador,
                    metodo_resolucao, score_confianca)
                VALUES (:id, 'CAMARA', '204554', 'FUZZY', 0.8700)
                """).param("id", id).update();

        Path destino = exportador.exportar(dir);
        String leiaMe = Files.readString(destino.resolve("LEIA-ME.md"),
                                         StandardCharsets.UTF_8);

        // Sem normalizar as quebras, a asserção dependeria de onde o texto
        // quebra a linha — e passaria a falhar ao reescrever um parágrafo.
        String corrido = leiaMe.replaceAll("\\s+", " ");
        assertThat(corrido).contains("vínculos por similaridade: **1**");
        assertThat(corrido).contains("sem revisão humana: **1**");
        assertThat(leiaMe).contains("CC BY 4.0");
    }

    /** Arquivo citado que muda embaixo de quem citou não é evidência. */
    @Test
    void o_instantaneo_do_dia_nao_e_sobrescrito(@TempDir Path dir) {
        candidato("Maria Da Silva");
        exportador.exportar(dir);

        assertThatThrownBy(() -> exportador.exportar(dir))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("nao e sobrescrito");
    }

    @Test
    void o_manifesto_declara_o_que_e_calculo_nosso(@TempDir Path dir) throws IOException {
        candidato("Maria Da Silva");

        Path destino = exportador.exportar(dir);
        String manifesto = Files.readString(destino.resolve("manifesto.json"),
                                            StandardCharsets.UTF_8);

        assertThat(manifesto)
            .contains("votos_derivados_por_nos")
            .contains("vinculos_fuzzy_sem_revisao_humana")
            .contains("gerado_em");
    }

    private UUID candidato(String nome) {
        var id = jdbc.sql("INSERT INTO politico (nome_civil) VALUES (:n) RETURNING id")
            .param("n", nome).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO candidatura (politico_id, sq_candidato_tse, ano_eleicao, cargo,
                                         esfera, uf, partido_sigla, status)
                VALUES (:id, 'sq-' || gen_random_uuid(), 2026, 'DEPUTADO_FEDERAL',
                        'FEDERAL', 'SP', 'XYZ', 'DEFERIDO')
                """).param("id", id).update();
        return id;
    }
}
