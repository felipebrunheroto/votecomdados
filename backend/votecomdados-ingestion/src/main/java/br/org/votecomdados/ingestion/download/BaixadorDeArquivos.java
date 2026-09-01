package br.org.votecomdados.ingestion.download;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Baixa arquivo de fonte pública, perguntando antes se ele mudou.
 *
 * <h2>O incremental usa o frescor que a fonte já publica</h2>
 *
 * A Câmara responde {@code 304 Not Modified} a {@code If-Modified-Since}
 * (verificado em 31/08/2026). Isso torna o ciclo diário quase gratuito: quando
 * nada mudou, a resposta tem <b>zero byte</b> e o job encerra sem trabalho.
 *
 * <p>É também o que dispensa a máquina de paginação, rate limit e circuit
 * breaker que um incremental por REST exigiria — a mesma máquina que o B3
 * apontou como sintoma de padrão de acesso equivocado. Um caminho de código em
 * vez de dois, e o watermark passa a ser o {@code Last-Modified} da própria
 * fonte, em vez de um relógio nosso tentando adivinhar o dela.
 */
@Component
public class BaixadorDeArquivos {

    private static final Logger log = LoggerFactory.getLogger(BaixadorDeArquivos.class);

    /** RFC 1123, que é o formato de data do HTTP. */
    private static final DateTimeFormatter HTTP_DATA =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
            .withZone(ZoneOffset.UTC);

    private final HttpClient http;
    private final Duration timeout;

    BaixadorDeArquivos(@Value("${votecomdados.download.timeout-segundos:120}") long segundos) {
        this.timeout = Duration.ofSeconds(segundos);
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    /**
     * @param desde watermark da última execução; {@code null} baixa sempre
     * @return vazio quando a fonte respondeu 304 — nada mudou, nada a fazer
     */
    public Optional<ArquivoBaixado> baixarSeMudou(URI origem, Path destino, Instant desde) {
        var pedido = HttpRequest.newBuilder(origem)
            .timeout(timeout)
            .header("Accept", "*/*");
        if (desde != null) {
            pedido.header("If-Modified-Since", HTTP_DATA.format(desde));
        }

        try {
            HttpResponse<Path> resposta = http.send(
                pedido.build(), HttpResponse.BodyHandlers.ofFile(destino));

            if (resposta.statusCode() == 304) {
                log.info("{}: nao mudou desde {}", nomeDe(origem), desde);
                Files.deleteIfExists(destino);
                return Optional.empty();
            }
            if (resposta.statusCode() != 200) {
                throw new IllegalStateException(
                    "fonte respondeu " + resposta.statusCode() + " para " + origem);
            }

            Instant modificadoEm = resposta.headers().firstValue("last-modified")
                .map(v -> Instant.from(HTTP_DATA.parse(v)))
                // Fonte sem Last-Modified: o watermark passa a ser o instante da
                // coleta. Menos preciso, e ainda assim monotônico.
                .orElseGet(Instant::now);

            long bytes = Files.size(destino);
            log.info("{}: {} bytes, modificado em {}", nomeDe(origem), bytes, modificadoEm);
            return Optional.of(new ArquivoBaixado(destino, modificadoEm, bytes));

        } catch (IOException e) {
            throw new IllegalStateException("falha ao baixar " + origem, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("download interrompido: " + origem, e);
        }
    }

    private static String nomeDe(URI origem) {
        String caminho = origem.getPath();
        int barra = caminho.lastIndexOf('/');
        return barra < 0 ? caminho : caminho.substring(barra + 1);
    }

    /**
     * @param modificadoEm o {@code Last-Modified} da fonte — é ele que vira
     *                     watermark, e não o relógio local
     */
    public record ArquivoBaixado(Path caminho, Instant modificadoEm, long bytes) {}
}
