package br.org.votecomdados.ingestion.senado;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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

    private final HttpClient http;
    private final ObjectMapper json;
    private final Duration timeout;

    ClienteDoSenado(ObjectMapper json,
                    @Value("${votecomdados.download.timeout-segundos:120}") long segundos) {
        this.json = json;
        this.timeout = Duration.ofSeconds(segundos);
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    public JsonNode buscar(URI endereco) {
        var pedido = HttpRequest.newBuilder(endereco)
            .timeout(timeout)
            .header("Accept", "application/json")
            .build();

        try {
            HttpResponse<String> resposta = http.send(pedido, HttpResponse.BodyHandlers.ofString());

            if (resposta.statusCode() != 200) {
                throw new IllegalStateException(
                    "senado respondeu " + resposta.statusCode() + " para " + endereco);
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
            throw new IllegalStateException("falha ao consultar " + endereco, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("consulta interrompida: " + endereco, e);
        }
    }
}
