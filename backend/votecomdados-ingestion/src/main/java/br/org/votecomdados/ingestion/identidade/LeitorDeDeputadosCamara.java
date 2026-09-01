package br.org.votecomdados.ingestion.identidade;

import br.org.votecomdados.core.dominio.Enums.Fonte;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Traduz uma linha de {@code deputados.csv} da Câmara.
 *
 * <p>Layout <b>verificado contra o arquivo real</b> em 30/08/2026 (7.889
 * linhas). O identificador vem do fim da URI — a Câmara não publica o id em
 * coluna própria neste arquivo, só embutido em
 * {@code .../api/v2/deputados/204554}.
 */
@Component
public class LeitorDeDeputadosCamara {

    public ParlamentarDaCasa ler(JsonNode linha) {
        return new ParlamentarDaCasa(
            Fonte.CAMARA,
            idDaUri(texto(linha, "uri")),
            texto(linha, "nome"),
            texto(linha, "nomeCivil"),
            data(linha, "dataNascimento"),
            // `ufNascimento` é onde a pessoa nasceu, não onde se elegeu. Serve
            // de âncora fraca para desempate de homônimo, e só isso — usá-la
            // como UF do mandato produziria vínculo errado.
            texto(linha, "ufNascimento"));
    }

    private static String idDaUri(String uri) {
        if (uri == null) return null;
        int barra = uri.lastIndexOf('/');
        return barra < 0 ? uri : uri.substring(barra + 1);
    }

    private static String texto(JsonNode no, String campo) {
        JsonNode v = no.get(campo);
        if (v == null || v.isNull()) return null;
        String s = v.asString().trim();
        return s.isEmpty() ? null : s;
    }

    private static LocalDate data(JsonNode no, String campo) {
        String s = texto(no, campo);
        if (s == null) return null;
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            // 11% das linhas históricas não têm nascimento; para elas a
            // resolução determinística não se aplica, e é o esperado.
            return null;
        }
    }
}
