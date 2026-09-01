package br.org.votecomdados.ingestion.coorte;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * CPF → {@code cpf_hmac}. Nunca o contrário.
 *
 * <h2>Por que HMAC e não hash</h2>
 *
 * O espaço de CPFs válidos é de ~10¹⁰ com dígito verificador. Uma GPU comum
 * enumera isso inteiro em segundos e monta uma tabela completa — um SHA-256
 * puro seria, na prática, uma forma reversível de guardar o CPF, e chamá-lo de
 * "anonimização" seria autoengano. A chave secreta é o que torna a enumeração
 * offline inviável.
 *
 * <h2>Por que a chave nunca tem padrão</h2>
 *
 * Um valor de fallback em código transformaria o pepper em segredo público no
 * primeiro deploy mal configurado — e ninguém perceberia, porque tudo
 * continuaria funcionando. Aqui a aplicação se recusa a subir sem ele.
 *
 * <h2>Para que serve, exatamente</h2>
 *
 * Ligar candidaturas da mesma pessoa entre eleições diferentes <b>dentro do
 * TSE</b>. Não serve para casar com a Câmara: verificado em 30/08/2026 que a
 * coluna {@code cpf} de {@code deputados.csv} vem vazia nas 7.889 linhas. Esse
 * casamento usa nome civil + data de nascimento.
 *
 * <p>Terminada a coorte, o campo é expurgado — ver
 * {@code RepositorioDeCoorte.expurgarCpfHmac()}.
 */
@Component
public class CalculadoraDeHmac {

    private static final String ALGORITMO = "HmacSHA256";

    private final byte[] pepper;

    CalculadoraDeHmac(@Value("${votecomdados.cpf.pepper:}") String pepper) {
        if (pepper == null || pepper.isBlank()) {
            throw new IllegalStateException(
                "votecomdados.cpf.pepper nao configurado. Sem pepper, o cpf_hmac "
                + "vira hash simples — enumeravel em segundos, e equivalente a "
                + "guardar o CPF. Injete o segredo pelo gerenciador do provedor.");
        }
        this.pepper = pepper.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * @param cpf com ou sem pontuação; só os dígitos entram no cálculo
     * @return 64 caracteres hex, ou {@code null} se o CPF vier vazio da fonte
     */
    public String hmacDe(String cpf) {
        if (cpf == null) return null;
        String digitos = cpf.replaceAll("\\D", "");
        if (digitos.isEmpty()) return null;
        // A fonte às vezes traz o CPF sem os zeros à esquerda. Sem normalizar,
        // a mesma pessoa geraria HMACs diferentes entre eleições e a trajetória
        // se partiria em duas — silenciosamente.
        if (digitos.length() < 11) {
            digitos = "0".repeat(11 - digitos.length()) + digitos;
        }
        try {
            Mac mac = Mac.getInstance(ALGORITMO);
            mac.init(new SecretKeySpec(pepper, ALGORITMO));
            return HexFormat.of().formatHex(mac.doFinal(digitos.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("falha ao calcular HMAC do CPF", e);
        }
    }
}
