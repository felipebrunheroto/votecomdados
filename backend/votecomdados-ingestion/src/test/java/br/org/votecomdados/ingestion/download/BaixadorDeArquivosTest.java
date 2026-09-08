package br.org.votecomdados.ingestion.download;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O incremental depende de a fonte responder 304 — e depende de nós fazermos a
 * pergunta certa. Um servidor real, aqui, é mais barato que um mock e prova as
 * duas coisas.
 */
class BaixadorDeArquivosTest {

    private static final DateTimeFormatter HTTP_DATA =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
            .withZone(ZoneOffset.UTC);

    /** Truncado ao segundo: é a resolução que o HTTP tem. */
    private static final Instant MODIFICADO_EM =
        Instant.parse("2026-08-31T06:50:48Z");

    private HttpServer servidor;
    private URI origem;
    private final AtomicInteger corposEntregues = new AtomicInteger();
    private final AtomicInteger tentativasInstavel = new AtomicInteger();
    private final AtomicInteger tentativasSumido = new AtomicInteger();
    // 3 tentativas com espera de 10ms: exercita a retentativa sem transformar
    // a suite em espera. Producao usa 4 tentativas comecando em 2s.
    private final BaixadorDeArquivos baixador = new BaixadorDeArquivos(30, 3, 10);

    @BeforeEach
    void subirServidor() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/arquivo.csv", troca -> {
            String seModificadoDesde = troca.getRequestHeaders()
                .getFirst("If-Modified-Since");
            troca.getResponseHeaders().add("Last-Modified", HTTP_DATA.format(MODIFICADO_EM));

            boolean naoMudou = seModificadoDesde != null
                && !Instant.from(HTTP_DATA.parse(seModificadoDesde)).isBefore(MODIFICADO_EM);
            if (naoMudou) {
                troca.sendResponseHeaders(304, -1);
                troca.close();
                return;
            }
            byte[] corpo = "\"id\";\"valor\"\n\"1\";\"conteudo\"\n"
                .getBytes(StandardCharsets.UTF_8);
            corposEntregues.incrementAndGet();
            troca.sendResponseHeaders(200, corpo.length);
            try (var saida = troca.getResponseBody()) {
                saida.write(corpo);
            }
        });
        servidor.createContext("/quebrado.csv", troca -> {
            troca.sendResponseHeaders(500, -1);
            troca.close();
        });
        // Some nas duas primeiras tentativas e entrega na terceira: e o
        // comportamento do portal da Camara observado em 07/09/2026, que
        // derrubou uma execucao inteira com HttpConnectTimeoutException.
        servidor.createContext("/instavel.csv", troca -> {
            int vez = tentativasInstavel.incrementAndGet();
            if (vez < 3) {
                troca.sendResponseHeaders(503, -1);
                troca.close();
                return;
            }
            byte[] corpo = "\"id\";\"valor\"\n\"1\";\"veio\"\n"
                .getBytes(StandardCharsets.UTF_8);
            troca.sendResponseHeaders(200, corpo.length);
            try (var saida = troca.getResponseBody()) {
                saida.write(corpo);
            }
        });
        servidor.createContext("/sumido.csv", troca -> {
            tentativasSumido.incrementAndGet();
            troca.sendResponseHeaders(404, -1);
            troca.close();
        });
        // Anuncia mais do que entrega. O HttpClient rejeita o corpo curto
        // sozinho; o que se prova aqui e que o pedaco ja escrito nao fica para
        // tras com o nome definitivo -- um CSV cortado passa no COPY sem
        // reclamar, so com menos linhas.
        servidor.createContext("/truncado.csv", troca -> {
            byte[] parcial = "\"id\";\"valor\"\n".getBytes(StandardCharsets.UTF_8);
            troca.sendResponseHeaders(200, parcial.length + 500L);
            try (var saida = troca.getResponseBody()) {
                saida.write(parcial);
            }
        });
        servidor.start();
        origem = URI.create("http://127.0.0.1:" + servidor.getAddress().getPort()
                            + "/arquivo.csv");
    }

    @AfterEach
    void pararServidor() {
        servidor.stop(0);
    }

    @Test
    void baixa_quando_nunca_foi_baixado(@TempDir Path dir) throws IOException {
        var destino = dir.resolve("arquivo.csv");

        var baixado = baixador.baixarSeMudou(origem, destino, null);

        assertThat(baixado).isPresent();
        assertThat(baixado.get().modificadoEm()).isEqualTo(MODIFICADO_EM);
        assertThat(Files.readString(destino)).contains("conteudo");
    }

    /** O ciclo diário sobre dado estável precisa custar quase nada. */
    @Test
    void nao_baixa_de_novo_quando_a_fonte_diz_que_nao_mudou(@TempDir Path dir) {
        var destino = dir.resolve("arquivo.csv");
        baixador.baixarSeMudou(origem, destino, null);

        var segunda = baixador.baixarSeMudou(origem, dir.resolve("outra.csv"),
                                             MODIFICADO_EM);

        assertThat(segunda).as("304 significa que nao ha trabalho a fazer").isEmpty();
        assertThat(corposEntregues.get()).isEqualTo(1);
    }

    @Test
    void baixa_de_novo_quando_a_fonte_mudou(@TempDir Path dir) {
        var anterior = MODIFICADO_EM.minusSeconds(86_400);

        var baixado = baixador.baixarSeMudou(origem, dir.resolve("arquivo.csv"), anterior);

        assertThat(baixado).isPresent();
        assertThat(corposEntregues.get()).isEqualTo(1);
    }

    /** 304 não pode deixar arquivo pela metade no disco. */
    @Test
    void resposta_304_nao_deixa_arquivo_vazio_para_tras(@TempDir Path dir) {
        var destino = dir.resolve("arquivo.csv");

        baixador.baixarSeMudou(origem, destino, MODIFICADO_EM);

        assertThat(Files.exists(destino))
            .as("um CSV vazio seria carregado como zero linhas, em silencio")
            .isFalse();
    }

    @Test
    void erro_da_fonte_falha_alto_em_vez_de_devolver_vazio(@TempDir Path dir) {
        var quebrado = URI.create("http://127.0.0.1:" + servidor.getAddress().getPort()
                                  + "/quebrado.csv");

        assertThatThrownBy(() ->
                baixador.baixarSeMudou(quebrado, dir.resolve("x.csv"), null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("500");
    }

    /** Erro tratado como "nada mudou" avançaria o watermark sobre uma janela vazia. */
    @Test
    void corpo_truncado_nao_deixa_arquivo_parcial_para_tras(@TempDir Path dir) {
        URI truncado = URI.create("http://127.0.0.1:" + servidor.getAddress().getPort()
                                  + "/truncado.csv");

        assertThatThrownBy(() -> baixador.baixarSeMudou(truncado, dir.resolve("t.csv"), null))
            .isInstanceOf(IllegalStateException.class);

        assertThat(dir.resolve("t.csv"))
            .as("arquivo parcial nao pode ficar para tras: a proxima etapa o carregaria")
            .doesNotExist();
    }

    /**
     * O backfill historico sao 125 downloads em sequencia. Sem retentativa, um
     * soluco de rede em qualquer um deles mata a execucao inteira -- foi o que
     * aconteceu em 07/09/2026, com HttpConnectTimeoutException.
     */
    @Test
    void fonte_instavel_e_repetida_ate_entregar(@TempDir Path dir) {
        URI instavel = URI.create("http://127.0.0.1:" + servidor.getAddress().getPort()
                                  + "/instavel.csv");

        var baixado = baixador.baixarSeMudou(instavel, dir.resolve("i.csv"), null);

        assertThat(baixado).isPresent();
        assertThat(tentativasInstavel.get())
            .as("duas recusas antes de entregar")
            .isEqualTo(3);
    }

    /**
     * 404 e a fonte dizendo que o pedido esta errado, nao que ela esta mal
     * agora. Repetir so gastaria tempo -- e num backfill de 25 anos, gastaria
     * 25 vezes.
     */
    @Test
    void erro_permanente_nao_e_repetido(@TempDir Path dir) {
        URI sumido = URI.create("http://127.0.0.1:" + servidor.getAddress().getPort()
                                + "/sumido.csv");

        assertThatThrownBy(() -> baixador.baixarSeMudou(sumido, dir.resolve("s.csv"), null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("404");

        assertThat(tentativasSumido.get())
            .as("4xx e definitivo: uma tentativa so")
            .isEqualTo(1);
    }

    @Test
    void o_endereco_dos_arquivos_da_camara_segue_o_padrao_do_portal() {
        assertThat(ArquivosDaCamara.votacoes(2026).toString())
            .isEqualTo("https://dadosabertos.camara.leg.br/arquivos/votacoes/csv/votacoes-2026.csv");
        assertThat(ArquivosDaCamara.votos(2026).toString()).endsWith("votacoesVotos-2026.csv");
        assertThat(ArquivosDaCamara.doAno(2026)).hasSize(5);
    }
}
