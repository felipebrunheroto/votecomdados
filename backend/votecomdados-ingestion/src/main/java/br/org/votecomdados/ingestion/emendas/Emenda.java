package br.org.votecomdados.ingestion.emendas;

import br.org.votecomdados.core.dominio.Enums.LocalidadeEmenda;
import java.math.BigDecimal;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Uma emenda, já traduzida da forma que a CGU devolve.
 *
 * <p>{@code politicoId} é nulável de propósito: nossa base é a coorte de 2026,
 * e parlamentar que não se candidatou este ano não existe nela. São 8,3% dos
 * autores de 2025. Emenda sem perfil onde exibi-la é fato da realidade, não
 * erro de ingestão — e descartá-la faria a página do município mentir por
 * omissão, mostrando menos dinheiro do que a cidade recebeu.
 *
 * <p>Os seis valores são guardados inteiros. {@code valorPago} sozinho conta
 * 84,2% do desembolso: quem olhar só ele dirá que Santos recebeu R$ 0,00 em
 * 2025, quando a cidade recebeu R$ 600 mil por restos a pagar.
 */
public record Emenda(
    String codigo,
    int ano,
    String numero,
    String tipo,
    String codigoAutor,
    String autorNome,
    String autorOrigemNome,
    UUID politicoId,
    String localidadeBruta,
    LocalidadeEmenda localidadeTipo,
    String municipioNome,
    String uf,
    String funcao,
    String subfuncao,
    BigDecimal valorEmpenhado,
    BigDecimal valorLiquidado,
    BigDecimal valorPago,
    BigDecimal valorRestoInscrito,
    BigDecimal valorRestoCancelado,
    BigDecimal valorRestoPago
) {

    /** Monta a partir da linha crua, sem ainda saber de quem ela é. */
    public static Emenda de(JsonNode l) {
        String codigo = LeitorDeEmenda.texto(l, "codigoEmenda");
        String numero = LeitorDeEmenda.texto(l, "numeroEmenda");
        String nomeCru = LeitorDeEmenda.texto(l, "nomeAutor");
        String localidade = LeitorDeEmenda.texto(l, "localidadeDoGasto");
        LocalidadeEmenda tipoLocal = LeitorDeEmenda.classificar(localidade);

        return new Emenda(
            codigo,
            l.get("ano") == null ? 0 : l.get("ano").asInt(),
            numero,
            LeitorDeEmenda.texto(l, "tipoEmenda"),
            LeitorDeEmenda.codigoDoAutor(codigo, numero),
            LeitorDeEmenda.autorNome(nomeCru),
            LeitorDeEmenda.autorOrigemNome(nomeCru),
            null,
            localidade,
            tipoLocal,
            // A CHECK do schema exige os dois nulos fora de MUNICIPIO, e os
            // dois preenchidos dentro. Derivar aqui, do mesmo texto que gerou
            // a classificação, é o que mantém as duas coisas coerentes.
            tipoLocal == LocalidadeEmenda.MUNICIPIO ? LeitorDeEmenda.municipio(localidade) : null,
            tipoLocal == LocalidadeEmenda.MUNICIPIO ? LeitorDeEmenda.uf(localidade) : null,
            LeitorDeEmenda.texto(l, "funcao"),
            LeitorDeEmenda.texto(l, "subfuncao"),
            LeitorDeEmenda.valor(LeitorDeEmenda.texto(l, "valorEmpenhado")),
            LeitorDeEmenda.valor(LeitorDeEmenda.texto(l, "valorLiquidado")),
            LeitorDeEmenda.valor(LeitorDeEmenda.texto(l, "valorPago")),
            LeitorDeEmenda.valor(LeitorDeEmenda.texto(l, "valorRestoInscrito")),
            LeitorDeEmenda.valor(LeitorDeEmenda.texto(l, "valorRestoCancelado")),
            LeitorDeEmenda.valor(LeitorDeEmenda.texto(l, "valorRestoPago"))
        );
    }

    public Emenda comPolitico(UUID id) {
        return new Emenda(codigo, ano, numero, tipo, codigoAutor, autorNome, autorOrigemNome,
            id, localidadeBruta, localidadeTipo, municipioNome, uf, funcao, subfuncao,
            valorEmpenhado, valorLiquidado, valorPago,
            valorRestoInscrito, valorRestoCancelado, valorRestoPago);
    }
}
