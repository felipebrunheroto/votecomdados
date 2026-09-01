package br.org.votecomdados.ingestion.massa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.org.votecomdados.core.dominio.Enums.Fonte;
import br.org.votecomdados.core.dominio.Enums.TipoJob;
import br.org.votecomdados.ingestion.download.ArquivosDaCamara;
import br.org.votecomdados.ingestion.download.JobIncremental.EnderecosDoAno;
import br.org.votecomdados.ingestion.execucao.ControleDeExecucaoService;
import br.org.votecomdados.ingestion.execucao.Execucao;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
 * A orquestração que faltava desde o W6: {@link JobDeBackfillCamara} por ano,
 * numa série — a peça que fecha o A2.
 *
 * <h2>O que este teste NÃO tenta provar</h2>
 *
 * A correção da derivação em si já tem teste dedicado
 * ({@code DerivacaoDeAusenciaTest}). O que só existe aqui é a ORQUESTRAÇÃO:
 * o laço por ano, o download incondicional, e derivar/finalizar exatamente
 * <b>uma vez</b>, depois de todo ano pedido — não por ano.
 *
 * <p>Os dois "anos" servidos neste teste apontam para o MESMO conteúdo golden
 * (não há um segundo ano real de amostra no repositório). Isso ainda prova o
 * que importa: o laço visita os dois, baixa os dois sem condicional, e o
 * resultado final é idempotente — repetir a carga do mesmo conteúdo não
 * duplica nada.
 */
@SpringBootTest
@Testcontainers
class JobDeBackfillTest {

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

    private static final Path GOLDEN = Path.of("..", "..", "db", "golden");
    private static final int ANO_1 = 2024;
    private static final int ANO_2 = 2025;

    @Autowired JobDeBackfill job;
    @Autowired ControleDeExecucaoService controle;
    @Autowired JdbcClient jdbc;

    HttpServer servidor;
    final AtomicInteger requisicoes = new AtomicInteger();
    final Set<String> comIfModifiedSince = java.util.concurrent.ConcurrentHashMap.newKeySet();
    Execucao execucao;

    @BeforeEach
    void preparar() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        for (int ano : List.of(ANO_1, ANO_2)) {
            servirGolden("/" + ano + "/proposicoes.csv", "camara-proposicoes-2026-amostra.csv");
            servirGolden("/" + ano + "/temas.csv", "camara-proposicoesTemas-2026-amostra.csv");
            servirGolden("/" + ano + "/autores.csv", "camara-proposicoesAutores-2026-amostra.csv");
            servirGolden("/" + ano + "/votacoes.csv", "camara-votacoes-2026-amostra.csv");
            servirGolden("/" + ano + "/votos.csv", "camara-votacoesVotos-2026-amostra.csv");
        }
        servidor.start();
        execucao = controle.iniciar(Fonte.CAMARA, TipoJob.BACKFILL, "{}");
    }

    @AfterEach
    void limpar() {
        servidor.stop(0);
        controle.close();
        jdbc.sql("DELETE FROM politico").update();
        jdbc.sql("DELETE FROM votacao").update();
        jdbc.sql("DELETE FROM proposicao").update();
        jdbc.sql("DELETE FROM ingestao_execucao").update();
    }

    @Test
    void carrega_todos_os_anos_do_intervalo(@TempDir Path dir) {
        deputadosDaAmostraNaCoorte();

        var r = job.executar(execucao, ANO_1, ANO_2, dir, this::enderecosDoAno);

        assertThat(r.anosProcessados()).containsExactly(ANO_1, ANO_2);
        assertThat(r.votacoes()).isPositive();
        assertThat(r.votos()).isPositive();
        assertThat(r.materias()).isPositive();
        assertThat(contar("votacao")).isPositive();
    }

    /**
     * O histórico nunca foi lido: não existe watermark a perguntar, então
     * nenhuma requisição pode levar {@code If-Modified-Since} — ao contrário
     * do incremental.
     */
    @Test
    void baixa_sem_condicional(@TempDir Path dir) {
        deputadosDaAmostraNaCoorte();

        job.executar(execucao, ANO_1, ANO_2, dir, this::enderecosDoAno);

        assertThat(requisicoes.get()).isEqualTo(10); // 5 arquivos x 2 anos
        assertThat(comIfModifiedSince)
            .as("backfill nunca pergunta 'mudou desde quando' -- o ano nunca foi lido")
            .isEmpty();
    }

    /** Retomar com --desde mais alto não toca no ano já carregado antes. */
    @Test
    void retomada_processa_so_o_intervalo_pedido(@TempDir Path dir) {
        deputadosDaAmostraNaCoorte();

        var r = job.executar(execucao, ANO_2, ANO_2, dir, this::enderecosDoAno);

        assertThat(r.anosProcessados()).containsExactly(ANO_2);
        assertThat(requisicoes.get()).isEqualTo(5); // só o ano pedido
    }

    @Test
    void desde_maior_que_ate_falha_alto(@TempDir Path dir) {
        assertThatThrownBy(() -> job.executar(execucao, ANO_2, ANO_1, dir, this::enderecosDoAno))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("posterior");
    }

    /**
     * O que prova que derivar/finalizar rodaram: um político fora da amostra
     * de votos, mas em exercício durante as duas votações, aparece como
     * AUSENTE — e o perfil dele fica pré-calculável.
     */
    @Test
    void deriva_ausencia_e_finaliza_apos_o_intervalo_inteiro(@TempDir Path dir) {
        deputadosDaAmostraNaCoorte();
        var ausente = candidatoEmExercicioSemVoto();

        job.executar(execucao, ANO_1, ANO_2, dir, this::enderecosDoAno);

        assertThat(jdbc.sql("""
                SELECT count(*) FROM voto_nominal
                 WHERE politico_id = :id AND origem_registro = 'DERIVADO' AND voto = 'AUSENTE'
                """).param("id", ausente).query(Long.class).single()).isPositive();

        assertThat(jdbc.sql("SELECT possui_atuacao_legislativa FROM politico WHERE id = :id")
            .param("id", ausente).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("""
                SELECT possui_atuacao_legislativa FROM perfil_leitura WHERE politico_id = :id
                """).param("id", ausente).query(Boolean.class).single()).isTrue();
    }

    @Test
    void reexecutar_o_mesmo_intervalo_nao_duplica(@TempDir Path dir) {
        deputadosDaAmostraNaCoorte();
        job.executar(execucao, ANO_1, ANO_2, dir, this::enderecosDoAno);
        long votacoes = contar("votacao");
        long votos = contar("voto_nominal");
        long proposicoes = contar("proposicao");
        controle.close();

        var proxima = controle.iniciar(Fonte.CAMARA, TipoJob.BACKFILL, "{}");
        job.executar(proxima, ANO_1, ANO_2, dir, this::enderecosDoAno);

        assertThat(contar("votacao")).isEqualTo(votacoes);
        assertThat(contar("voto_nominal")).isEqualTo(votos);
        assertThat(contar("proposicao")).isEqualTo(proposicoes);
    }

    @Test
    void os_enderecos_de_producao_seguem_o_padrao_do_portal() {
        assertThat(ArquivosDaCamara.votacoes(2010).toString())
            .isEqualTo("https://dadosabertos.camara.leg.br/arquivos/votacoes/csv/votacoes-2010.csv");
        assertThat(EnderecosDoAno.daCamara(2010).todos()).hasSize(5);
    }

    // ---------------------------------------------------------------- auxiliares

    private EnderecosDoAno enderecosDoAno(int ano) {
        String base = "http://127.0.0.1:" + servidor.getAddress().getPort() + "/" + ano;
        return new EnderecosDoAno(
            URI.create(base + "/proposicoes.csv"), URI.create(base + "/temas.csv"),
            URI.create(base + "/autores.csv"), URI.create(base + "/votacoes.csv"),
            URI.create(base + "/votos.csv"));
    }

    private void servirGolden(String caminho, String arquivo) {
        servidor.createContext(caminho, troca -> {
            requisicoes.incrementAndGet();
            if (troca.getRequestHeaders().getFirst("If-Modified-Since") != null) {
                comIfModifiedSince.add(caminho);
            }
            troca.getResponseHeaders().add("Last-Modified",
                "Mon, 31 Aug 2026 06:50:48 GMT");
            byte[] corpo = Files.readAllBytes(GOLDEN.resolve(arquivo));
            troca.sendResponseHeaders(200, corpo.length);
            try (var saida = troca.getResponseBody()) {
                saida.write(corpo);
            }
        });
    }

    private long contar(String tabela) {
        return jdbc.sql("SELECT count(*) FROM " + tabela).query(Long.class).single();
    }

    private List<String> deputadosDaAmostraNaCoorte() {
        var ids = new java.util.ArrayList<String>();
        try {
            var linhas = Files.readAllLines(
                GOLDEN.resolve("camara-votacoesVotos-2026-amostra.csv"), StandardCharsets.UTF_8);
            var cabecalho = List.of(linhas.getFirst().replace("﻿", "").split(";"));
            int coluna = cabecalho.indexOf("\"deputado_id\"");
            for (String linha : linhas.subList(1, Math.min(6, linhas.size()))) {
                String id = linha.split(";")[coluna].replace("\"", "");
                if (!ids.contains(id)) ids.add(id);
            }
            // Quem vota e quem assina matéria são conjuntos DIFERENTES na
            // amostra — sem pelo menos um autor na coorte, carregarProposicoes
            // não grava nenhuma matéria (só entra o que tem autor da coorte).
            var linhasAutor = Files.readAllLines(
                GOLDEN.resolve("camara-proposicoesAutores-2026-amostra.csv"),
                StandardCharsets.UTF_8);
            var cabecalhoAutor = List.of(linhasAutor.getFirst().replace("﻿", "").split(";"));
            int colunaAutor = cabecalhoAutor.indexOf("\"idDeputadoAutor\"");
            for (String linha : linhasAutor.subList(1, Math.min(3, linhasAutor.size()))) {
                String id = linha.split(";")[colunaAutor].replace("\"", "");
                if (!ids.contains(id)) ids.add(id);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        for (String id : ids) {
            UUID politico = jdbc.sql(
                "INSERT INTO politico (nome_civil) VALUES ('Deputado ' || :id) RETURNING id")
                .param("id", id).query(UUID.class).single();
            jdbc.sql("""
                    INSERT INTO candidatura (politico_id, sq_candidato_tse, ano_eleicao,
                                             cargo, esfera, uf, partido_sigla, status)
                    VALUES (:p, 'sq-' || :id, 2026, 'DEPUTADO_FEDERAL', 'FEDERAL', 'SP',
                            'XYZ', 'NAO_INFORMADO')
                    """).param("p", politico).param("id", id).update();
            jdbc.sql("""
                    INSERT INTO identificador_externo (politico_id, sistema, identificador)
                    VALUES (:p, 'CAMARA', :id)
                    """).param("p", politico).param("id", id).update();
        }
        return ids;
    }

    /** Em exercício durante as duas votações da amostra (2026-02 e 2026-03), sem voto nenhum. */
    private UUID candidatoEmExercicioSemVoto() {
        var id = jdbc.sql("""
                INSERT INTO politico (nome_civil, nome_urna) VALUES ('Faltante Teste', 'Faltante Teste')
                RETURNING id
                """).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO candidatura (politico_id, sq_candidato_tse, ano_eleicao, cargo,
                                         esfera, uf, partido_sigla, status)
                VALUES (:id, 'sq-faltante', 2026, 'DEPUTADO_FEDERAL', 'FEDERAL', 'SP',
                        'XYZ', 'NAO_INFORMADO')
                """).param("id", id).update();
        jdbc.sql("""
                INSERT INTO mandato_exercicio (politico_id, casa, situacao, situacao_origem, inicio)
                VALUES (:id, 'CAMARA', 'EXERCICIO', 'Exercício', :inicio)
                """).param("id", id).param("inicio", LocalDate.of(2026, 1, 1)).update();
        return id;
    }
}
