package br.org.votecomdados.ingestion.emendas;

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
 * Fala com a API de Dados do Portal da Transparência (CGU).
 *
 * <h2>A chave nunca aparece em log</h2>
 *
 * Ela vai no cabeçalho {@code chave-api-dados} e em nenhum outro lugar. As
 * mensagens de erro citam o código HTTP e a página, jamais a URL montada com
 * cabeçalhos — é o tipo de vazamento que só se descobre quando o log já está
 * no CloudWatch.
 *
 * <h2>O recuo não é precaução teórica</h2>
 *
 * No spike de 25/09/2026 a coleta morreu com <b>HTTP 504 na página 136</b> de
 * 421. Não foi limite de taxa — estávamos a 150 req/min contra um teto de 400
 * —, foi a API ficando lenta. Sem recuo, uma falha assim deixa o ano
 * carregado pela metade, e metade de um ano é pior que nenhum: os números
 * agregados ficam plausíveis e errados. Naquele dia, a amostra parcial dizia
 * que 22,6% do dinheiro tinha município; o ano inteiro diz 3,4%.
 */
@Component
public class ClienteDoPortal {

    private static final Logger log = LoggerFactory.getLogger(ClienteDoPortal.class);

    private static final String BASE =
        "https://api.portaldatransparencia.gov.br/api-de-dados/emendas";

    /**
     * 400 req/min no horário normal, 700 entre 00:00 e 06:00, e uso acima
     * disso SUSPENDE o token. 250ms entre páginas dá ~240 req/min: folga
     * confortável, e o job roda de madrugada, dentro da janela mais generosa.
     */
    private static final Duration PAUSA = Duration.ofMillis(250);

    private static final int TENTATIVAS = 4;

    private final HttpClient http;
    private final ObjectMapper json;
    private final String chave;
    private final Duration timeout;

    ClienteDoPortal(ObjectMapper json,
                    @Value("${votecomdados.portal.chave:}") String chave,
                    @Value("${votecomdados.download.timeout-segundos:120}") long segundos) {
        this.json = json;
        this.chave = chave;
        this.timeout = Duration.ofSeconds(segundos);
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    public boolean configurado() {
        return chave != null && !chave.isBlank();
    }

    /**
     * Uma página de emendas do ano. Lista vazia significa fim da paginação.
     *
     * @throws IllegalStateException se a página não puder ser obtida após as
     *     tentativas — e é para estourar mesmo: ano pela metade é pior que ano
     *     nenhum.
     */
    public JsonNode pagina(int ano, int pagina) {
        if (!configurado()) {
            throw new IllegalStateException(
                "votecomdados.portal.chave nao configurada. A API da CGU exige "
                + "chave; sem ela nenhuma emenda e coletada.");
        }
        URI endereco = URI.create(BASE + "?ano=" + ano + "&pagina=" + pagina);

        IllegalStateException ultima = null;
        for (int tentativa = 1; tentativa <= TENTATIVAS; tentativa++) {
            try {
                var pedido = HttpRequest.newBuilder(endereco)
                    .timeout(timeout)
                    .header("chave-api-dados", chave)
                    .header("Accept", "application/json")
                    .build();

                HttpResponse<String> r = http.send(pedido, HttpResponse.BodyHandlers.ofString());

                if (r.statusCode() == 200) {
                    dormir(PAUSA);
                    return json.readTree(r.body());
                }
                if (r.statusCode() == 401 || r.statusCode() == 403) {
                    // Não adianta insistir: ou a chave está errada, ou foi
                    // suspensa por excesso de uso. Repetir só piora.
                    throw new IllegalStateException(
                        "CGU recusou a chave (HTTP " + r.statusCode() + "). "
                        + "Verifique votecomdados.portal.chave.");
                }
                ultima = new IllegalStateException(
                    "CGU respondeu " + r.statusCode() + " na pagina " + pagina);
            } catch (IOException e) {
                ultima = new IllegalStateException(
                    "falha de rede na pagina " + pagina + ": " + e.getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrompido na pagina " + pagina, e);
            }

            if (tentativa < TENTATIVAS) {
                Duration espera = Duration.ofSeconds(3L * tentativa);
                log.warn("pagina {} falhou (tentativa {}/{}); nova tentativa em {}s",
                    pagina, tentativa, TENTATIVAS, espera.toSeconds());
                dormir(espera);
            }
        }
        throw ultima;
    }

    private static void dormir(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
