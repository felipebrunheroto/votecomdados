package br.org.votecomdados.ingestion.download;

import static org.assertj.core.api.Assertions.assertThat;

import br.org.votecomdados.core.dominio.Enums.Fonte;
import br.org.votecomdados.core.dominio.Enums.TipoJob;
import br.org.votecomdados.ingestion.execucao.ControleDeExecucaoService;
import br.org.votecomdados.ingestion.execucao.Execucao;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
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
 * O ciclo diário: serve os arquivos golden por HTTP real e verifica que o job
 * só trabalha quando a fonte diz que mudou.
 */
@SpringBootTest
@Testcontainers
class JobIncrementalTest {

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

    private static final DateTimeFormatter HTTP_DATA =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
            .withZone(ZoneOffset.UTC);
    private static final Instant MODIFICADO_EM = Instant.parse("2026-08-31T06:50:48Z");
    private static final Path GOLDEN = Path.of("..", "..", "db", "golden");

    @Autowired JobIncremental job;
    @Autowired ControleDeExecucaoService controle;
    @Autowired JdbcClient jdbc;

    HttpServer servidor;
    JobIncremental.EnderecosDoAno enderecos;
    final AtomicInteger corposEntregues = new AtomicInteger();
    Execucao execucao;

    @BeforeEach
    void preparar() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servirGolden("/proposicoes.csv", "camara-proposicoes-2026-amostra.csv");
        servirGolden("/temas.csv", "camara-proposicoesTemas-2026-amostra.csv");
        servirGolden("/autores.csv", "camara-proposicoesAutores-2026-amostra.csv");
        servirGolden("/votacoes.csv", "camara-votacoes-2026-amostra.csv");
        servirGolden("/votos.csv", "camara-votacoesVotos-2026-amostra.csv");
        servidor.start();

        String base = "http://127.0.0.1:" + servidor.getAddress().getPort();
        enderecos = new JobIncremental.EnderecosDoAno(
            URI.create(base + "/proposicoes.csv"), URI.create(base + "/temas.csv"),
            URI.create(base + "/autores.csv"), URI.create(base + "/votacoes.csv"),
            URI.create(base + "/votos.csv"));

        execucao = controle.iniciar(Fonte.CAMARA, TipoJob.INCREMENTAL, "{}");
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
    void primeira_execucao_carrega_tudo(@TempDir Path dir) {
        deputadosDaAmostraNaCoorte();

        var r = job.executar(execucao, 2026, dir, enderecos);

        assertThat(r.houveMudanca()).isTrue();
        assertThat(r.watermarkNovo()).isEqualTo(MODIFICADO_EM);
        assertThat(r.votacoes()).isPositive();
        assertThat(contar("votacao")).isPositive();
    }

    /**
     * O ciclo sobre dado estável precisa custar quase nada — é o que torna
     * aceitável rodar todo dia até o fim da legislatura.
     */
    @Test
    void ciclo_seguinte_sem_mudanca_nao_faz_trabalho_nenhum(@TempDir Path dir) {
        deputadosDaAmostraNaCoorte();
        var primeira = job.executar(execucao, 2026, dir, enderecos);
        int aposPrimeira = corposEntregues.get();

        // O fluxo real: a execução fecha, gravando o watermark, e a seguinte o
        // lê. Abrir duas ao mesmo tempo é justamente o que o controle impede.
        controle.concluir(execucao, primeira.watermarkNovo(), 0, 0);
        var segunda = controle.iniciar(Fonte.CAMARA, TipoJob.INCREMENTAL, "{}");
        assertThat(segunda.watermarkAnterior())
            .as("a execucao seguinte precisa herdar o marcador")
            .isEqualTo(MODIFICADO_EM);

        var r = job.executar(segunda, 2026, dir, enderecos);

        assertThat(r.houveMudanca()).isFalse();
        assertThat(corposEntregues.get())
            .as("nenhum corpo novo: a fonte respondeu 304")
            .isEqualTo(aposPrimeira);
    }

    /** Marcador à frente do dado faz o ciclo seguinte pular a janela. */
    @Test
    void sem_mudanca_o_watermark_fica_onde_estava(@TempDir Path dir) {
        var comWatermark = new Execucao(execucao.id(), execucao.fonte(),
                                        execucao.tipoJob(), MODIFICADO_EM);

        var r = job.executar(comWatermark, 2026, dir, enderecos);

        assertThat(r.houveMudanca()).isFalse();
        assertThat(r.watermarkNovo()).isEqualTo(MODIFICADO_EM);
    }

    @Test
    void o_watermark_e_o_last_modified_da_fonte_e_nao_o_relogio_local(@TempDir Path dir) {
        deputadosDaAmostraNaCoorte();

        var r = job.executar(execucao, 2026, dir, enderecos);

        assertThat(r.watermarkNovo())
            .as("relogio local diverge do da fonte, e o marcador precisa ser da fonte")
            .isEqualTo(MODIFICADO_EM);
    }

    /** Voto novo muda quem faltou: a derivação tem de acompanhar no mesmo ciclo. */
    @Test
    void o_ciclo_recalcula_a_ausencia_e_a_projecao(@TempDir Path dir) {
        var ids = deputadosDaAmostraNaCoorte();
        // Alguém da coorte em exercício que NÃO aparece no arquivo de votos.
        var ausente = jdbc.sql(
            "INSERT INTO politico (nome_civil) VALUES ('Quem Faltou') RETURNING id")
            .query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO candidatura (politico_id, sq_candidato_tse, ano_eleicao, cargo,
                                         esfera, uf, partido_sigla, status)
                VALUES (:id, 'sq-ausente', 2026, 'DEPUTADO_FEDERAL', 'FEDERAL', 'SP',
                        'XYZ', 'NAO_INFORMADO')
                """).param("id", ausente).update();
        jdbc.sql("""
                INSERT INTO mandato_exercicio (politico_id, casa, situacao, situacao_origem,
                                               inicio, fim)
                VALUES (:id, 'CAMARA', 'EXERCICIO', 'Exercício', '2023-02-01', NULL)
                """).param("id", ausente).update();

        job.executar(execucao, 2026, dir, enderecos);

        assertThat(ids).isNotEmpty();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM voto_nominal
                 WHERE politico_id = :id AND voto = 'AUSENTE' AND origem_registro = 'DERIVADO'
                """).param("id", ausente).query(Long.class).single())
            .as("a ausencia publicada nao pode ficar de ontem").isEqualTo(1);

        assertThat(contar("perfil_leitura"))
            .as("a projecao precisa refletir o ciclo").isPositive();
    }

    // ---------------------------------------------------------------- auxiliares

    private void servirGolden(String caminho, String arquivo) {
        servidor.createContext(caminho, troca -> {
            String desde = troca.getRequestHeaders().getFirst("If-Modified-Since");
            troca.getResponseHeaders().add("Last-Modified", HTTP_DATA.format(MODIFICADO_EM));
            if (desde != null
                && !Instant.from(HTTP_DATA.parse(desde)).isBefore(MODIFICADO_EM)) {
                troca.sendResponseHeaders(304, -1);
                troca.close();
                return;
            }
            byte[] corpo = Files.readAllBytes(GOLDEN.resolve(arquivo));
            corposEntregues.incrementAndGet();
            troca.sendResponseHeaders(200, corpo.length);
            try (var saida = troca.getResponseBody()) {
                saida.write(corpo);
            }
        });
    }

    private long contar(String tabela) {
        return jdbc.sql("SELECT count(*) FROM " + tabela).query(Long.class).single();
    }

    private java.util.List<String> deputadosDaAmostraNaCoorte() {
        var ids = new java.util.ArrayList<String>();
        try {
            var linhas = Files.readAllLines(
                GOLDEN.resolve("camara-votacoesVotos-2026-amostra.csv"),
                StandardCharsets.UTF_8);
            var cabecalho = java.util.List.of(
                linhas.getFirst().replace("﻿", "").split(";"));
            int coluna = cabecalho.indexOf("\"deputado_id\"");
            for (String linha : linhas.subList(1, Math.min(6, linhas.size()))) {
                String id = linha.split(";")[coluna].replace("\"", "");
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
}
