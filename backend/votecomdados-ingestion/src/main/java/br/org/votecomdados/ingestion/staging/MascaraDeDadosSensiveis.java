package br.org.votecomdados.ingestion.staging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.regex.Pattern;

/**
 * Rede de proteção no log — <b>não</b> é a garantia.
 *
 * <p>A garantia é a allowlist do {@link RedatorDeCamposSensiveis}: o CPF não
 * chega ao disco porque nunca é selecionado. Esta classe cobre o caminho que a
 * allowlist não alcança: o worker logando um payload ao tratar erro, com o dado
 * ainda cru na memória. É o vazamento mais fácil de cometer, porque só acontece
 * quando algo já deu errado — e é onde ninguém está olhando.
 *
 * <p>Sendo por padrão, é necessariamente aproximada: mascara o que <i>parece</i>
 * CPF ou título de eleitor. Confiar nela como mecanismo primário seria trocar
 * uma garantia estrutural por heurística. A ordem importa — allowlist primeiro,
 * máscara como último recurso.
 */
public class MascaraDeDadosSensiveis extends MessageConverter {

    /** 11 dígitos, com ou sem pontuação: 123.456.789-01 ou 12345678901. */
    private static final Pattern CPF =
        Pattern.compile("(?<!\\d)\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}(?!\\d)");

    /** Título de eleitor: 12 dígitos. */
    private static final Pattern TITULO =
        Pattern.compile("(?<!\\d)\\d{12}(?!\\d)");

    @Override
    public String convert(ILoggingEvent evento) {
        return mascarar(super.convert(evento));
    }

    /** Visível para teste: é a única forma de provar que a máscara funciona. */
    public static String mascarar(String texto) {
        if (texto == null || texto.isEmpty()) return texto;
        String saida = TITULO.matcher(texto).replaceAll("«titulo-mascarado»");
        return CPF.matcher(saida).replaceAll("«cpf-mascarado»");
    }
}
