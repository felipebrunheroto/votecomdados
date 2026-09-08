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
    private final int tentativas;
    private final Duration esperaInicial;

    BaixadorDeArquivos(@Value("${votecomdados.download.timeout-segundos:120}") long segundos,
                       @Value("${votecomdados.download.tentativas:4}") int tentativas,
                       @Value("${votecomdados.download.espera-inicial-ms:2000}") long esperaMs) {
        this.timeout = Duration.ofSeconds(segundos);
        this.tentativas = tentativas;
        this.esperaInicial = Duration.ofMillis(esperaMs);
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
        RuntimeException ultima = null;

        for (int tentativa = 1; tentativa <= tentativas; tentativa++) {
            try {
                return umaTentativa(origem, destino, desde);
            } catch (FalhaTransitoria e) {
                ultima = e;
                if (tentativa == tentativas) break;
                Duration espera = esperaInicial.multipliedBy(1L << (tentativa - 1));
                log.warn("{}: {} (tentativa {}/{}); nova tentativa em {}s",
                         nomeDe(origem), e.getMessage(), tentativa, tentativas,
                         espera.toSeconds());
                dormir(espera);
            }
        }

        // A queixa da ultima tentativa entra na mensagem: sem ela, o log de
        // producao mostraria so "falhou apos 4 tentativas", e o motivo ficaria
        // enterrado na cadeia de causas.
        throw new IllegalStateException(
            "falha ao baixar " + origem + " apos " + tentativas + " tentativa(s): "
            + (ultima == null ? "motivo desconhecido" : ultima.getMessage()), ultima);
    }

    private Optional<ArquivoBaixado> umaTentativa(URI origem, Path destino, Instant desde) {
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
                // O corpo do erro ja foi escrito em disco por ofFile: uma
                // pagina de 404 com nome de CSV.
                apagarParcial(destino);
                String queixa = "fonte respondeu " + resposta.statusCode() + " para " + origem;
                // 5xx é a fonte mal agora; 4xx é a fonte dizendo que o pedido
                // está errado, e repeti-lo só perde tempo.
                if (resposta.statusCode() >= 500) {
                    throw new FalhaTransitoria(queixa, null);
                }
                throw new IllegalStateException(queixa);
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
            // Corpo curto o HttpClient ja rejeita sozinho -- mas o que chegou
            // antes da falha fica em disco, com o nome definitivo. A etapa
            // seguinte carregaria esse pedaco como se fosse o arquivo inteiro,
            // e um CSV cortado passa no COPY sem reclamar: so com menos linhas.
            apagarParcial(destino);
            throw new FalhaTransitoria(e.getMessage() == null ? e.toString() : e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("download interrompido: " + origem, e);
        }
    }

    private static void dormir(Duration espera) {
        try {
            Thread.sleep(espera.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("espera entre tentativas interrompida", e);
        }
    }

    /** Falha que vale repetir: rede, timeout, corpo cortado, 5xx. */
    private static class FalhaTransitoria extends RuntimeException {
        FalhaTransitoria(String mensagem, Throwable causa) {
            super(mensagem, causa);
        }
    }

    private static String nomeDe(URI origem) {
        String caminho = origem.getPath();
        int barra = caminho.lastIndexOf('/');
        return barra < 0 ? caminho : caminho.substring(barra + 1);
    }

    /**
     * Apagar é melhor esforço: se falhar, o erro que importa é o do download,
     * e mascará-lo com um problema de disco só atrapalharia o diagnóstico.
     */
    private static void apagarParcial(Path destino) {
        try {
            Files.deleteIfExists(destino);
        } catch (IOException falhaAoApagar) {
            log.warn("nao consegui apagar o parcial {}", destino, falhaAoApagar);
        }
    }

    /**
     * @param modificadoEm o {@code Last-Modified} da fonte — é ele que vira
     *                     watermark, e não o relógio local
     */
    public record ArquivoBaixado(Path caminho, Instant modificadoEm, long bytes) {}
}
