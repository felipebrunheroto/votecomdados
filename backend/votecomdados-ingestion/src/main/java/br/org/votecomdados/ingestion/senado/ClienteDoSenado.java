package br.org.votecomdados.ingestion.senado;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Fala com a API do Senado, e recusa o que não é JSON.
 *
 * <h2>A defesa não é estilo — é a pior falha silenciosa que a fonte tem</h2>
 *
 * Verificado em 31/08/2026 (golden do W11) e de novo em 01/09/2026: a mesma
 * URL responde {@code Content-Type: application/json} com
 * {@code Accept: application/json} e {@code Content-Type: text/csv} com
 * {@code Accept: text/csv} — <b>e o CSV omite o array {@code votos} por
 * completo</b>, sem erro, com HTTP 200. Um cliente que confiasse no Accept
 * enviado, sem checar o que voltou, carregaria votações inteiras sem nenhum
 * voto individual — silenciosamente, porque "votação sem voto" também é um
 * estado válido (simbólica). Este cliente checa o {@code Content-Type} da
 * <b>resposta</b>, não presume que o pedido foi atendido como pedido.
 */
@Component
public class ClienteDoSenado {

    private static final Logger log = LoggerFactory.getLogger(ClienteDoSenado.class);

    private final HttpClient http;
    private final ObjectMapper json;
    private final Duration timeout;
    private final int tentativas;
    private final Duration esperaInicial;

    ClienteDoSenado(ObjectMapper json,
                    @Value("${votecomdados.download.timeout-segundos:120}") long segundos,
                    @Value("${votecomdados.download.tentativas:4}") int tentativas,
                    @Value("${votecomdados.download.espera-inicial-ms:2000}") long esperaMs) {
        this.json = json;
        this.timeout = Duration.ofSeconds(segundos);
        this.tentativas = tentativas;
        this.esperaInicial = Duration.ofMillis(esperaMs);
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    /**
     * Busca com recuo, como {@code BaixadorDeArquivos} já fazia.
     *
     * <h2>Por que isto passou a existir</h2>
     *
     * Em 29/09/2026 a ingestão do Senado falhou inteira porque a API do órgão
     * respondeu <b>um</b> 503 — indisponibilidade de instantes: minutos depois
     * a mesma URL devolvia 200 em 0,4s. O watermark foi preservado e não houve
     * estado parcial, mas a fonte ficou um dia sem atualizar e exigiu
     * reexecução manual.
     *
     * <p>O cliente da Câmara já recuava desde sempre, com os mesmos quatro
     * ataques e espera dobrando. A assimetria era só descuido: este cliente
     * nasceu depois e não herdou o cuidado.
     *
     * <h2>5xx repete, 4xx não</h2>
     *
     * A distinção é a mesma do baixador: 5xx é a fonte mal agora, e vale
     * insistir; 4xx é a fonte dizendo que o pedido está errado, e repeti-lo só
     * gasta tempo. Content-Type inesperado também não se repete — a API
     * responder CSV é decisão dela sobre o pedido, não falha passageira.
     */
    public JsonNode buscar(URI endereco) {
        RuntimeException ultima = null;

        for (int tentativa = 1; tentativa <= tentativas; tentativa++) {
            try {
                return umaTentativa(endereco);
            } catch (FalhaTransitoria e) {
                ultima = e;
                if (tentativa == tentativas) break;
                Duration espera = esperaInicial.multipliedBy(1L << (tentativa - 1));
                log.warn("senado: {} (tentativa {}/{}); nova tentativa em {}s",
                         e.getMessage(), tentativa, tentativas, espera.toSeconds());
                dormir(espera);
            }
        }

        // A queixa da ultima tentativa entra na mensagem: sem ela o log diria
        // so "falhou apos 4 tentativas", e o motivo ficaria enterrado.
        throw new IllegalStateException(
            "senado falhou apos " + tentativas + " tentativa(s): "
            + (ultima == null ? "sem causa registrada" : ultima.getMessage()), ultima);
    }

    private static void dormir(Duration espera) {
        try {
            Thread.sleep(espera.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("espera entre tentativas interrompida", e);
        }
    }

    /** Falha que vale repetir: rede, timeout, 5xx. */
    private static class FalhaTransitoria extends RuntimeException {
        FalhaTransitoria(String mensagem, Throwable causa) {
            super(mensagem, causa);
        }
    }

    private JsonNode umaTentativa(URI endereco) {
        var pedido = HttpRequest.newBuilder(endereco)
            .timeout(timeout)
            .header("Accept", "application/json")
            .build();

        try {
            HttpResponse<String> resposta = http.send(pedido, HttpResponse.BodyHandlers.ofString());

            if (resposta.statusCode() != 200) {
                String queixa = "respondeu " + resposta.statusCode() + " para " + endereco;
                if (resposta.statusCode() >= 500) {
                    throw new FalhaTransitoria(queixa, null);
                }
                throw new IllegalStateException("senado " + queixa);
            }

            String tipo = resposta.headers().firstValue("content-type").orElse("");
            if (!tipo.contains("json")) {
                throw new IllegalStateException(
                    "senado respondeu Content-Type '" + tipo + "' para " + endereco
                    + "; esperado JSON. A mesma API responde CSV e descarta o array "
                    + "'votos' em silencio — ver ClienteDoSenado.");
            }

            return json.readTree(resposta.body());
        } catch (IOException e) {
            // Rede: vale repetir, igual ao baixador.
            throw new FalhaTransitoria("falha ao consultar " + endereco, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("consulta interrompida: " + endereco, e);
        }
    }
}
