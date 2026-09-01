package br.org.votecomdados.ingestion.identidade;

import br.org.votecomdados.core.dominio.Enums.Fonte;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Traduz o registro achatado de um senador (ver {@code senado.OrquestradorDoSenado})
 * para {@link ParlamentarDaCasa}.
 *
 * <h2>Por que o registro chega achatado, e não a resposta bruta da API</h2>
 *
 * A API do Senado aninha {@code IdentificacaoParlamentar} — e para
 * parlamentares em exercício esse bloco vem com {@code EmailParlamentar} e
 * {@code UrlFotoParlamentar}, verificado em 01/09/2026. O redator de campos
 * sensíveis ({@code RedatorDeCamposSensiveis}) filtra por NOME DE CAMPO no
 * primeiro nível; um campo aninhado permitido é copiado <b>inteiro</b>, sem
 * descer. Declarar {@code IdentificacaoParlamentar} na allowlist vazaria
 * e-mail e foto para o staging. Por isso o orquestrador monta um objeto raso
 * só com o que interessa, e é esse objeto — não a resposta da API — que vira
 * {@code linha} e é gravado.
 *
 * <h2>O Senado permite casamento determinístico</h2>
 *
 * Ao contrário da Alesp, {@code /senador/{codigo}} publica
 * {@code DataNascimento} completa (dia, mês E ano) — verificado em
 * 01/09/2026. Com {@code nomeCivil} e {@code dataNascimento} preenchidos, a
 * resolução de identidade usa o mesmo caminho determinístico da Câmara; não
 * precisou de um {@code porNomeParlamentar} próprio.
 */
@Component
public class LeitorDeSenadores {

    public ParlamentarDaCasa ler(JsonNode registro) {
        return new ParlamentarDaCasa(
            Fonte.SENADO,
            texto(registro, "codigoParlamentar"),
            texto(registro, "nomeParlamentar"),
            texto(registro, "nomeCompletoParlamentar"),
            data(registro, "dataNascimento"),
            texto(registro, "ufParlamentar"));
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
            // Verificado: a imensa maioria dos senadores tem DataNascimento
            // completa. Os raríssimos sem ela caem no mesmo caminho de quem
            // não tem data nenhuma — nada se perde, a resolução só fica mais
            // cautelosa (pendente de curadoria em vez de determinística).
            return null;
        }
    }
}
