package br.org.votecomdados.ingestion.senado;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * A defesa contra o pior modo de falha silenciosa da fonte: a mesma URL
 * responde JSON completo ou CSV sem o array {@code votos}, dependendo só do
 * cabeçalho {@code Accept} — verificado contra a API real em 01/09/2026.
 * Confiar que o pedido foi atendido como pedido, sem checar a resposta,
 * carregaria votações inteiras sem nenhum voto individual.
 */
class ClienteDoSenadoTest {

    private HttpServer servidor;
    private final ClienteDoSenado cliente = new ClienteDoSenado(new JsonMapper(), 10);

    @BeforeEach
    void subirServidor() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/json", troca -> {
            byte[] corpo = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
            troca.sendResponseHeaders(200, corpo.length);
            try (var saida = troca.getResponseBody()) {
                saida.write(corpo);
            }
        });
        // O caso real: a mesma consulta, respondida como CSV. É o que acontece
        // quando o Accept não é honrado ou é enviado errado — o cliente
        // precisa recusar isto, não desserializar CSV como se fosse JSON.
        servidor.createContext("/csv-silencioso", troca -> {
            byte[] corpo = "codigoSessaoVotacao;dataSessao\n123;2026-07-01\n"
                .getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "text/csv;charset=UTF-8");
            troca.sendResponseHeaders(200, corpo.length);
            try (var saida = troca.getResponseBody()) {
                saida.write(corpo);
            }
        });
        servidor.createContext("/erro", troca -> {
            troca.sendResponseHeaders(500, -1);
            troca.close();
        });
        servidor.start();
    }

    @AfterEach
    void pararServidor() {
        servidor.stop(0);
    }

    @Test
    void aceita_resposta_json() {
        var resultado = cliente.buscar(enderecoDe("/json"));

        assertThat(resultado.get("ok").asBoolean()).isTrue();
    }

    @Test
    void recusa_csv_mesmo_com_http_200() {
        assertThatThrownBy(() -> cliente.buscar(enderecoDe("/csv-silencioso")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("text/csv")
            .as("o corpo tem 'dataSessao' mas nao tem 'votos' — desserializar "
                + "isso como JSON perderia o voto individual em silencio");
    }

    @Test
    void erro_da_fonte_falha_alto() {
        assertThatThrownBy(() -> cliente.buscar(enderecoDe("/erro")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("500");
    }

    @Test
    void os_enderecos_seguem_o_que_foi_verificado_na_api_real() {
        var enderecos = EnderecosDoSenado.producao();
        assertThat(enderecos.votacoesDoAno(2026).toString())
            .isEqualTo("https://legis.senado.leg.br/dadosabertos/votacao?ano=2026");
        assertThat(enderecos.universoDeParlamentares().toString())
            .isEqualTo("https://legis.senado.leg.br/dadosabertos/senador/lista/legislatura/50/57");
        assertThat(enderecos.detalheDoParlamentar("5672").toString())
            .isEqualTo("https://legis.senado.leg.br/dadosabertos/senador/5672");
    }

    private URI enderecoDe(String caminho) {
        return URI.create("http://127.0.0.1:" + servidor.getAddress().getPort() + caminho);
    }
}
