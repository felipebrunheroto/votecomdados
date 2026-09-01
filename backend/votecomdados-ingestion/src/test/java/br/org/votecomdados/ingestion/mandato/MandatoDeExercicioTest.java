package br.org.votecomdados.ingestion.mandato;

import static org.assertj.core.api.Assertions.assertThat;

import br.org.votecomdados.core.dominio.Enums.CasaLegislativa;
import br.org.votecomdados.core.dominio.Enums.Fonte;
import br.org.votecomdados.core.dominio.Enums.TipoJob;
import br.org.votecomdados.ingestion.execucao.ControleDeExecucaoService;
import br.org.votecomdados.ingestion.execucao.Execucao;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
 * A base da derivação de ausência (B8), testada contra o histórico real.
 *
 * <p>O deputado 74374 da amostra tem 39 eventos e passa por exercício, licença,
 * fim de mandato, suplência e convocação — inclusive convocação e posse no
 * mesmo dia. É o caso que um teste sintético não teria imaginado.
 */
@SpringBootTest
@Testcontainers
class MandatoDeExercicioTest {

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

    private static final Path GOLDEN = Path.of("..", "..", "db", "golden",
        "camara-deputado-historico-amostra.jsonl");

    @Autowired ConstrutorDePeriodos construtor;
    @Autowired JobDeMandatos job;
    @Autowired RepositorioDeMandato repositorio;
    @Autowired ControleDeExecucaoService controle;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    Execucao execucao;

    @BeforeEach
    void abrir() {
        execucao = controle.iniciar(Fonte.CAMARA, TipoJob.BACKFILL, "{}");
    }

    @AfterEach
    void limpar() {
        controle.close();
        jdbc.sql("DELETE FROM politico").update();
        jdbc.sql("DELETE FROM staging.registro_rejeitado").update();
        jdbc.sql("DELETE FROM ingestao_execucao").update();
    }

    @Test
    void eventos_sem_situacao_nao_viram_periodo() throws IOException {
        var eventos = eventosDe(74374);
        long semSituacao = eventos.stream()
            .filter(e -> e.get("situacao").isNull()).count();
        assertThat(semSituacao).as("a amostra precisa ter esse caso").isPositive();

        var periodos = construtor.construir(eventos);

        assertThat(periodos).isNotEmpty();
        assertThat(periodos).allSatisfy(p ->
            assertThat(p.situacaoOrigem()).isNotNull());
    }

    /** Convocação às 14:56 e posse às 15:15 — em data, um dia só. */
    @Test
    void transicao_no_mesmo_dia_nao_gera_periodo_vazio() throws IOException {
        var periodos = construtor.construir(eventosDe(74374));

        assertThat(periodos).allSatisfy(p ->
            assertThat(p.fim() == null || p.fim().isAfter(p.inicio()))
                .as("periodo de %s comeca em %s e termina em %s",
                    p.situacaoOrigem(), p.inicio(), p.fim())
                .isTrue());
    }

    @Test
    void periodos_nao_se_sobrepoem_e_sao_ordenados() throws IOException {
        var periodos = construtor.construir(eventosDe(74374));

        for (int i = 0; i + 1 < periodos.size(); i++) {
            var atual = periodos.get(i);
            var seguinte = periodos.get(i + 1);
            assertThat(atual.fim()).isNotNull();
            assertThat(atual.fim()).isEqualTo(seguinte.inicio());
        }
        assertThat(periodos.getLast().fim()).as("o ultimo periodo segue vigente").isNull();
    }

    @Test
    void mudanca_de_partido_em_exercicio_nao_fatia_o_periodo() throws IOException {
        var periodos = construtor.construir(eventosDe(74374));

        for (int i = 0; i + 1 < periodos.size(); i++) {
            assertThat(periodos.get(i).situacaoOrigem())
                .as("dois periodos iguais e seguidos seriam ruido sem significado")
                .isNotEqualTo(periodos.get(i + 1).situacaoOrigem());
        }
    }

    /** Reprocessar tem de dar exatamente o mesmo resultado. */
    @Test
    void a_construcao_e_deterministica_apesar_das_datas_repetidas() throws IOException {
        var primeira = construtor.construir(eventosDe(74374));
        var segunda = construtor.construir(eventosDe(74374));

        assertThat(segunda).isEqualTo(primeira);
    }

    @Test
    void periodos_da_coorte_sao_gravados_com_o_rotulo_original() throws IOException {
        var politicoId = candidatoComVinculo("74374");

        var r = job.carregar(execucao, CasaLegislativa.CAMARA, "74374", eventosDe(74374));

        assertThat(r.periodos()).isPositive();
        assertThat(r.situacoesNaoMapeadas()).isZero();

        var origens = jdbc.sql("""
                SELECT DISTINCT situacao_origem FROM mandato_exercicio
                 WHERE politico_id = :id
                """).param("id", politicoId).query(String.class).list();
        assertThat(origens).contains("Exercício", "Licença");
    }

    /** O que a derivação de ausência vai perguntar, todo dia. */
    @Test
    void da_para_dizer_em_que_situacao_a_pessoa_estava_numa_data() throws IOException {
        var politicoId = candidatoComVinculo("74374");
        job.carregar(execucao, CasaLegislativa.CAMARA, "74374", eventosDe(74374));

        var em2019 = repositorio.situacaoNaData(politicoId, CasaLegislativa.CAMARA,
                                                LocalDate.of(2019, 1, 15));

        assertThat(em2019).as("em janeiro de 2019 estava licenciado, nao ausente")
            .contains("LICENCA");
    }

    @Test
    void recarregar_substitui_em_vez_de_duplicar() throws IOException {
        candidatoComVinculo("74374");

        int primeira = job.carregar(execucao, CasaLegislativa.CAMARA, "74374",
                                    eventosDe(74374)).periodos();
        int segunda = job.carregar(execucao, CasaLegislativa.CAMARA, "74374",
                                   eventosDe(74374)).periodos();

        assertThat(segunda).isEqualTo(primeira);
        assertThat(jdbc.sql("SELECT count(*) FROM mandato_exercicio")
            .query(Long.class).single()).isEqualTo(primeira);
    }

    /** O banco recusa sobreposição — e é o que impede marcar falta em licença. */
    @Test
    void o_banco_recusa_periodos_sobrepostos() throws IOException {
        var politicoId = candidatoComVinculo("74374");
        job.carregar(execucao, CasaLegislativa.CAMARA, "74374", eventosDe(74374));

        var erro = org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () ->
            jdbc.sql("""
                INSERT INTO mandato_exercicio
                    (politico_id, casa, situacao, situacao_origem, inicio, fim)
                VALUES (:id, 'CAMARA', 'EXERCICIO', 'Exercício', '2019-01-10', '2019-01-20')
                """).param("id", politicoId).update());

        assertThat(erro.getMessage()).containsIgnoringCase("mandato_exercicio");
    }

    @Test
    void quem_nao_esta_na_coorte_nao_gera_periodo() throws IOException {
        var r = job.carregar(execucao, CasaLegislativa.CAMARA, "74374", eventosDe(74374));

        assertThat(r.periodos()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM mandato_exercicio")
            .query(Long.class).single()).isZero();
    }

    @Test
    void situacao_sem_traducao_vai_para_quarentena_em_vez_de_virar_exercicio()
            throws IOException {
        candidatoComVinculo("74374");
        var inventada = json.readTree("""
            [{"dataHora": "2024-01-01T00:00", "situacao": "SITUACAO_QUE_A_CAMARA_INVENTOU",
              "condicaoEleitoral": "Titular"}]
            """);

        var r = job.carregar(execucao, CasaLegislativa.CAMARA, "74374", inventada);

        assertThat(r.situacoesNaoMapeadas()).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM staging.registro_rejeitado
                 WHERE motivo = 'SITUACAO_NAO_MAPEADA'
                """).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void todas_as_situacoes_da_amostra_real_tem_traducao() throws IOException {
        var mapeamento = repositorio.mapeamentoDe(Fonte.CAMARA);
        var semTraducao = new ArrayList<String>();

        for (int id : new int[]{74374, 74328, 141463, 204554}) {
            for (var p : construtor.construir(eventosDe(id))) {
                if (!mapeamento.containsKey(p.situacaoOrigem())) {
                    semTraducao.add(p.situacaoOrigem());
                }
            }
        }
        assertThat(semTraducao)
            .as("rotulo novo na fonte falha aqui, antes de virar quarentena em producao")
            .isEmpty();
    }

    // ---------------------------------------------------------------- auxiliares

    private UUID candidatoComVinculo(String identificador) {
        var id = jdbc.sql("""
                INSERT INTO politico (nome_civil) VALUES ('Parlamentar de Teste')
                RETURNING id
                """).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO candidatura (politico_id, sq_candidato_tse, ano_eleicao, cargo,
                                         esfera, uf, partido_sigla, status)
                VALUES (:id, 'sq-' || gen_random_uuid(), 2026, 'DEPUTADO_FEDERAL',
                        'FEDERAL', 'MS', 'XYZ', 'DEFERIDO')
                """).param("id", id).update();
        jdbc.sql("""
                INSERT INTO identificador_externo (politico_id, sistema, identificador)
                VALUES (:id, 'CAMARA', :ident)
                """).param("id", id).param("ident", identificador).update();
        return id;
    }

    private List<JsonNode> eventosDe(int idDeputado) throws IOException {
        for (String linha : Files.readAllLines(GOLDEN, StandardCharsets.UTF_8)) {
            JsonNode no = json.readTree(linha);
            if (no.get("idDeputado").asInt() == idDeputado) {
                var lista = new ArrayList<JsonNode>();
                no.get("eventos").forEach(lista::add);
                return lista;
            }
        }
        throw new IllegalStateException("deputado " + idDeputado + " nao esta na amostra");
    }
}
