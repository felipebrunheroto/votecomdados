package br.org.votecomdados.ingestion.alesp;

import br.org.votecomdados.core.dominio.Enums.Fonte;
import br.org.votecomdados.ingestion.identidade.ParlamentarDaCasa;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Traduz um registro de {@code deputados.xml} da Alesp.
 *
 * <h2>O identificador é IdSPL, e usar IdDeputado atribuiria votos à pessoa errada</h2>
 *
 * O cadastro publica <b>dois</b> identificadores, e o de nome mais óbvio é o
 * errado. Verificado em 31/08/2026:
 *
 * <ul>
 *   <li>{@code <IdDeputado>} em {@code deputados.xml} é o id do <b>portal</b>;</li>
 *   <li>{@code <IdDeputado>} em {@code comissoes_permanentes_votacoes.xml} e
 *       {@code <IdAutor>} em {@code documento_autor.xml} são o id do <b>SPL</b>,
 *       que o cadastro expõe como {@code <IdSPL>}.</li>
 * </ul>
 *
 * <p>São espaços de numeração diferentes com o <b>mesmo nome de elemento</b>.
 * Dos 94 deputados em exercício, 7 têm um {@code IdDeputado} que colide com o
 * id SPL de <b>outra pessoa</b>. No golden file estão dois deles: Carlão
 * Pignatari tem {@code IdDeputado} 431, e 431 é o id SPL de <b>Enio Tatto</b> —
 * casar pelo campo óbvio daria a Carlão Pignatari os 101 mil registros de
 * autoria de Enio Tatto.
 *
 * <p>O casamento por {@code IdSPL} foi conferido: os 94 do cadastro batem com
 * um autor, e em nenhum deles o nome diverge.
 *
 * <h2>Sem nome civil e sem ano de nascimento</h2>
 *
 * A Alesp publica {@code Aniversario} como <b>dia/mês</b> ("26/08"), sem ano, e
 * não publica nome civil. Por isso {@code nomeCivil} e {@code dataNascimento}
 * saem nulos: a resolução determinística que Câmara e Senado permitem não é
 * possível aqui, e forçar um valor só faria o casamento parecer mais forte do
 * que é.
 */
@Component
public class LeitorDeDeputadosAlesp {

    public ParlamentarDaCasa ler(JsonNode registro) {
        return new ParlamentarDaCasa(
            Fonte.ALESP,
            texto(registro, "IdSPL"),
            texto(registro, "NomeParlamentar"),
            null,
            null,
            // Deputado estadual da Alesp é sempre de SP; a UF é o que impede o
            // homônimo de outro estado de entrar como candidato ao vínculo.
            "SP");
    }

    private static String texto(JsonNode no, String campo) {
        JsonNode v = no.get(campo);
        if (v == null || v.isNull()) return null;
        String s = v.asString().trim();
        return s.isEmpty() ? null : s;
    }
}
