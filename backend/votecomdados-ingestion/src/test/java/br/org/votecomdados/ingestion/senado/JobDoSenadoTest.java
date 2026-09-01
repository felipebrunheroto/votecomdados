package br.org.votecomdados.ingestion.senado;

import static org.assertj.core.api.Assertions.assertThat;

import br.org.votecomdados.core.dominio.Enums.Fonte;
import br.org.votecomdados.core.dominio.Enums.TipoJob;
import br.org.votecomdados.ingestion.execucao.ControleDeExecucaoService;
import br.org.votecomdados.ingestion.execucao.Execucao;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * O Senado contra a amostra real: 4 votações, 324 votos, os 13 rótulos que a
 * Casa emite e uma votação secreta.
 *
 * <p>É a fonte que se comporta ao contrário da Câmara — publica a bancada
 * inteira —, e estes testes existem para garantir que a diferença é tratada
 * como diferença, e não achatada.
 */
@SpringBootTest
@Testcontainers
class JobDoSenadoTest {

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

    private static final Path GOLDEN =
        Path.of("..", "..", "db", "golden", "senado-votacao-amostra.jsonl");

    @Autowired JobDoSenado job;
    @Autowired ControleDeExecucaoService controle;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    Execucao execucao;

    @BeforeEach
    void abrir() {
        execucao = controle.iniciar(Fonte.SENADO, TipoJob.BACKFILL, "{}");
    }

    @AfterEach
    void limpar() {
        controle.close();
        jdbc.sql("DELETE FROM politico").update();
        jdbc.sql("DELETE FROM votacao").update();
        jdbc.sql("DELETE FROM staging.payload_bruto").update();
        jdbc.sql("DELETE FROM staging.registro_rejeitado").update();
        jdbc.sql("DELETE FROM ingestao_execucao").update();
    }

    @Test
    void carrega_as_votacoes_da_amostra_real() throws IOException {
        var r = job.carregar(execucao, votacoes());

        assertThat(r.votacoes()).isEqualTo(4);
        assertThat(jdbc.sql("SELECT count(*) FROM votacao WHERE casa = 'SENADO'")
            .query(Long.class).single()).isEqualTo(4);
    }

    /** Metade das votações do Senado é secreta — não é caso de borda. */
    @Test
    void votacao_secreta_e_marcada_como_tal() throws IOException {
        job.carregar(execucao, votacoes());

        assertThat(jdbc.sql("SELECT count(*) FROM votacao WHERE secreta").query(Long.class)
            .single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM votacao WHERE NOT secreta")
            .query(Long.class).single()).isEqualTo(3);
    }

    /**
     * Secreta continua NOMINAL: há registro de quem participou, só não de como
     * votou. Marcá-la simbólica esconderia a participação.
     */
    @Test
    void votacao_secreta_continua_nominal() throws IOException {
        job.carregar(execucao, votacoes());

        assertThat(jdbc.sql("SELECT DISTINCT tipo::text FROM votacao WHERE casa = 'SENADO'")
            .query(String.class).list()).containsExactly("NOMINAL");
    }

    /**
     * O rótulo mais frequente do Senado. Traduzi-lo para SIM/NAO seria inventar
     * posição; para AUSENTE, caluniar por omissão.
     */
    @Test
    void participacao_em_votacao_secreta_vira_SECRETO_e_nao_ausencia() throws IOException {
        todosOsSenadoresDaAmostraNaCoorte();

        job.carregar(execucao, votacoes());

        var pares = jdbc.sql("""
                SELECT DISTINCT voto::text || '|' || voto_origem FROM voto_nominal
                 WHERE voto_origem = 'Votou'
                """).query(String.class).list();

        assertThat(pares).containsExactly("SECRETO|Votou");
    }

    /** Presente e não votou não é falta — e a fonte distingue os dois. */
    @Test
    void presente_sem_registrar_voto_nao_vira_ausente() throws IOException {
        todosOsSenadoresDaAmostraNaCoorte();

        job.carregar(execucao, votacoes());

        assertThat(jdbc.sql("""
                SELECT DISTINCT voto::text FROM voto_nominal WHERE voto_origem = 'P-NRV'
                """).query(String.class).list()).containsExactly("PRESENTE_NAO_VOTOU");
    }

    @Test
    void licencas_e_ausencias_justificadas_sao_categorias_distintas() throws IOException {
        todosOsSenadoresDaAmostraNaCoorte();

        job.carregar(execucao, votacoes());

        var porRotulo = jdbc.sql("""
                SELECT voto_origem || '=' || voto::text FROM voto_nominal
                 WHERE voto_origem IN ('LS','LP','LAP','AP','MIS','NCom')
                 GROUP BY 1 ORDER BY 1
                """).query(String.class).list();

        assertThat(porRotulo)
            .contains("LS=LICENCIADO")
            .contains("AP=AUSENCIA_JUSTIFICADA")
            .contains("MIS=AUSENCIA_JUSTIFICADA")
            .contains("NCom=AUSENTE");
    }

    /** 'NA' não é voto, e não tem tradução honesta. Não se adivinha. */
    @Test
    void rotulo_sem_traducao_vai_para_quarentena_em_vez_de_ser_adivinhado()
            throws IOException {
        todosOsSenadoresDaAmostraNaCoorte();

        var r = job.carregar(execucao, votacoes());

        assertThat(r.rotulosSemTraducao()).containsExactly("NA");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM staging.registro_rejeitado
                 WHERE motivo = 'VALOR_VOTO_NAO_MAPEADO'
                """).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM voto_nominal WHERE voto_origem = 'NA'
                """).query(Long.class).single()).isZero();
    }

    /** No Senado nada é derivado: a Casa declara o universo inteiro. */
    @Test
    void nada_no_senado_e_calculo_nosso() throws IOException {
        todosOsSenadoresDaAmostraNaCoorte();

        job.carregar(execucao, votacoes());

        assertThat(jdbc.sql("""
                SELECT count(*) FROM voto_nominal vn JOIN votacao v ON v.id = vn.votacao_id
                 WHERE v.casa = 'SENADO' AND vn.origem_registro <> 'FONTE'
                """).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM voto_nominal vn JOIN votacao v ON v.id = vn.votacao_id
                 WHERE v.casa = 'SENADO' AND vn.voto_origem IS NULL
                """).query(Long.class).single()).isZero();
    }

    @Test
    void so_entram_os_votos_de_quem_esta_na_coorte() throws IOException {
        var doisNaCoorte = senadoresNaCoorte(2);

        var r = job.carregar(execucao, votacoes());

        assertThat(r.votos())
            .as("a amostra tem 324 linhas de voto; so 2 senadores sao da coorte")
            .isLessThan(324);
        assertThat(jdbc.sql("SELECT count(DISTINCT politico_id) FROM voto_nominal")
            .query(Long.class).single()).isEqualTo((long) doisNaCoorte.size());
    }

    @Test
    void recarregar_nao_duplica() throws IOException {
        todosOsSenadoresDaAmostraNaCoorte();
        job.carregar(execucao, votacoes());
        long votacoes = contar("votacao");
        long votos = contar("voto_nominal");

        job.carregar(execucao, votacoes());

        assertThat(contar("votacao")).isEqualTo(votacoes);
        assertThat(contar("voto_nominal")).isEqualTo(votos);
    }

    /** O payload cru do Senado também passa pela allowlist. */
    @Test
    void o_payload_do_senado_e_redigido_no_staging() throws IOException {
        job.carregar(execucao, votacoes());

        var staged = jdbc.sql("SELECT payload::text FROM staging.payload_bruto LIMIT 1")
            .query(String.class).single();

        assertThat(staged).contains("codigoSessaoVotacao").contains("votos");
        assertThat(staged)
            .as("campo nao declarado na allowlist nao pode aparecer")
            .doesNotContain("sexoParlamentar")
            .doesNotContain("informeLegislativo");
    }

    // ---------------------------------------------------------------- auxiliares

    private long contar(String tabela) {
        return jdbc.sql("SELECT count(*) FROM " + tabela).query(Long.class).single();
    }

    private List<JsonNode> votacoes() throws IOException {
        var lista = new ArrayList<JsonNode>();
        for (String linha : Files.readAllLines(GOLDEN, StandardCharsets.UTF_8)) {
            if (!linha.isBlank()) lista.add(json.readTree(linha));
        }
        return lista;
    }

    private List<String> todosOsSenadoresDaAmostraNaCoorte() throws IOException {
        return senadoresNaCoorte(Integer.MAX_VALUE);
    }

    private List<String> senadoresNaCoorte(int quantos) throws IOException {
        var codigos = new ArrayList<String>();
        for (JsonNode v : votacoes()) {
            for (JsonNode voto : v.get("votos")) {
                String c = voto.get("codigoParlamentar").asString();
                if (!codigos.contains(c)) codigos.add(c);
                if (codigos.size() >= quantos) break;
            }
            if (codigos.size() >= quantos) break;
        }
        for (String codigo : codigos) {
            UUID politico = jdbc.sql(
                "INSERT INTO politico (nome_civil) VALUES ('Senador ' || :c) RETURNING id")
                .param("c", codigo).query(UUID.class).single();
            jdbc.sql("""
                    INSERT INTO candidatura (politico_id, sq_candidato_tse, ano_eleicao,
                                             cargo, esfera, uf, partido_sigla, status)
                    VALUES (:p, 'sq-' || :c, 2026, 'SENADOR', 'FEDERAL', 'AC', 'XYZ',
                            'NAO_INFORMADO')
                    """).param("p", politico).param("c", codigo).update();
            jdbc.sql("""
                    INSERT INTO identificador_externo (politico_id, sistema, identificador)
                    VALUES (:p, 'SENADO', :c)
                    """).param("p", politico).param("c", codigo).update();
        }
        return codigos;
    }
}
