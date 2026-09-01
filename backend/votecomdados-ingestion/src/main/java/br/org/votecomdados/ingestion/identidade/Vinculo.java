package br.org.votecomdados.ingestion.identidade;

import br.org.votecomdados.core.dominio.Enums.MetodoResolucao;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * O resultado de tentar ligar um parlamentar da Casa a alguém da coorte.
 *
 * <p>São quatro desfechos, e confundir dois deles é o erro mais caro do
 * projeto:
 *
 * <ul>
 *   <li>{@link #resolvido} — casou com confiança; o vínculo vale.</li>
 *   <li>{@link #pendenteDeCuradoria} — casou por similaridade, abaixo do
 *       limiar. Fica gravado, mas <b>não conta como confirmado</b>.</li>
 *   <li>{@link #ambiguo} — mais de um candidato plausível. Não se escolhe: vai
 *       para curadoria, porque fundir dois homônimos num perfil só é
 *       exatamente o erro que destruiria a credibilidade da plataforma.</li>
 *   <li>{@link #foraDaCoorte} — não é candidato em 2026. <b>Esperado</b>, não
 *       defeito: a maioria dos parlamentares históricos cai aqui.</li>
 * </ul>
 */
public record Vinculo(
    Desfecho desfecho,
    UUID politicoId,
    MetodoResolucao metodo,
    BigDecimal score,
    String detalhe
) {

    public enum Desfecho { RESOLVIDO, PENDENTE_DE_CURADORIA, AMBIGUO, FORA_DA_COORTE }

    public static Vinculo resolvido(UUID politicoId) {
        return new Vinculo(Desfecho.RESOLVIDO, politicoId,
                           MetodoResolucao.DETERMINISTICO, null, null);
    }

    public static Vinculo pendenteDeCuradoria(UUID politicoId, BigDecimal score) {
        return new Vinculo(Desfecho.PENDENTE_DE_CURADORIA, politicoId,
                           MetodoResolucao.FUZZY, score,
                           "similaridade " + score + " abaixo do limiar; aguarda revisão");
    }

    public static Vinculo ambiguo(String detalhe) {
        return new Vinculo(Desfecho.AMBIGUO, null, null, null, detalhe);
    }

    public static Vinculo foraDaCoorte() {
        return new Vinculo(Desfecho.FORA_DA_COORTE, null, null, null,
                           "sem candidatura em 2026");
    }
}
