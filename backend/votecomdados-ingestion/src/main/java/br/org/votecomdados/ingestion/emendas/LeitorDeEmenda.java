package br.org.votecomdados.ingestion.emendas;

import br.org.votecomdados.core.dominio.Enums.LocalidadeEmenda;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Traduz uma linha da API de emendas da CGU para o nosso modelo.
 *
 * <p>Tudo aqui é função pura, sem rede nem banco, porque é o lugar onde a
 * fonte engana — e engana calado. Cada regra abaixo existe porque uma medição
 * sobre o ano de 2025 inteiro (6.311 emendas) mostrou que o caminho óbvio dava
 * resposta errada. Ver docs/DISCOVERY_EMENDAS.md.
 */
public final class LeitorDeEmenda {

    private LeitorDeEmenda() {}

    /** "ITAMARAJU - BA" — cidade e UF, o único caso atribuível a um município. */
    private static final Pattern MUNICIPIO = Pattern.compile("^(.+?)\\s+-\\s+([A-Z]{2})$");

    /** "BAHIA (UF)" — o estado inteiro. */
    private static final Pattern ESTADO = Pattern.compile("\\(UF\\)\\s*$", Pattern.CASE_INSENSITIVE);

    /**
     * "FULANO (EX-PARLAMENTAR BELTRANO, NOS TERMOS ART. 78 LDO 2025 E DA
     * MENSAGEM 95-CN, DE 06.11.25)".
     *
     * <p>Casou em 14 de 14 nomes anotados de 2025 — 110 linhas, R$ 473 mi. O
     * parse não é cosmético: o nome CRU, com a anotação, não casa com pessoa
     * nenhuma da nossa base, então sem isto o vínculo se perde inteiro.
     */
    private static final Pattern AUTORIA_TRANSFERIDA =
        Pattern.compile("^(.*?)\\s*\\(\\s*EX-PARLAMENTAR\\s+(.*?)\\s*(?:,|\\)).*$",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Nome do autor sem a anotação administrativa. */
    public static String autorNome(String nomeAutor) {
        if (nomeAutor == null) return null;
        Matcher m = AUTORIA_TRANSFERIDA.matcher(nomeAutor.trim());
        return (m.matches() ? m.group(1) : nomeAutor).trim();
    }

    /** Parlamentar de quem a emenda veio, ou null quando não houve transferência. */
    public static String autorOrigemNome(String nomeAutor) {
        if (nomeAutor == null) return null;
        Matcher m = AUTORIA_TRANSFERIDA.matcher(nomeAutor.trim());
        return m.matches() ? m.group(2).trim() : null;
    }

    /**
     * O código do autor, extraído de {@code codigoEmenda}.
     *
     * <p>Não existe campo próprio para ele: {@code autor} e {@code nomeAutor}
     * trazem os dois o mesmo nome puro. O código está nos dígitos 5 a 8 de
     * "202541840004" = ano 2025 + autor 4184 + número 0004, e confere-se contra
     * {@code numeroEmenda}, que repete os quatro últimos. Bateu em 6.311 de
     * 6.311 linhas; 628 códigos para 628 nomes, sem ambiguidade nos dois
     * sentidos.
     *
     * @return o código, ou null se o formato não for o esperado — caso em que
     *     é melhor não ter âncora do que ter uma inventada.
     */
    public static String codigoDoAutor(String codigoEmenda, String numeroEmenda) {
        if (codigoEmenda == null || codigoEmenda.length() != 12
            || !codigoEmenda.chars().allMatch(Character::isDigit)) {
            return null;
        }
        if (numeroEmenda != null) {
            String ultimos = codigoEmenda.substring(8);
            String esperado = numeroEmenda.trim();
            if (esperado.length() <= 4) {
                esperado = "0".repeat(4 - esperado.length()) + esperado;
                if (!ultimos.equals(esperado)) return null;
            }
        }
        return codigoEmenda.substring(4, 8);
    }

    public static LocalidadeEmenda classificar(String localidade) {
        if (localidade == null || localidade.isBlank()) return LocalidadeEmenda.OUTRO;
        String t = localidade.trim();
        if (MUNICIPIO.matcher(t).matches()) return LocalidadeEmenda.MUNICIPIO;
        if (ESTADO.matcher(t).find()) return LocalidadeEmenda.ESTADO;
        String semAcento = t.toUpperCase(Locale.ROOT)
            .replace('Ú', 'U').replace('Í', 'I').replace('Ó', 'O').replace('Á', 'A');
        if (semAcento.equals("NACIONAL")) return LocalidadeEmenda.NACIONAL;
        if (semAcento.equals("MULTIPLO")) return LocalidadeEmenda.MULTIPLO;
        return LocalidadeEmenda.OUTRO;
    }

    /** Nome da cidade, só quando a localidade é de município. */
    public static String municipio(String localidade) {
        if (localidade == null) return null;
        Matcher m = MUNICIPIO.matcher(localidade.trim());
        return m.matches() ? m.group(1).trim() : null;
    }

    /** UF, só quando a localidade é de município. */
    public static String uf(String localidade) {
        if (localidade == null) return null;
        Matcher m = MUNICIPIO.matcher(localidade.trim());
        return m.matches() ? m.group(2) : null;
    }

    /**
     * "2.359.960,00" para 2359960.00; "- 26.002,00" para -26002.00.
     *
     * <p>O sinal vem SEPARADO do número por espaço, e apareceu na primeira
     * linha real que a API devolveu. A versão ingênua estourava em
     * {@code new BigDecimal("- 26002.00")} e, com um catch devolvendo zero,
     * teria transformado um estorno de R$ 26 mil em "nada aconteceu".
     *
     * @return o valor, ou <b>null</b> quando não dá para ler. Nunca zero:
     *     zero indistinguível de ausência é exatamente o número errado que
     *     esta plataforma não pode publicar.
     */
    public static BigDecimal valor(String bruto) {
        if (bruto == null) return null;
        String t = bruto.trim();
        if (t.isEmpty()) return null;

        boolean negativo = t.startsWith("-");
        if (negativo) t = t.substring(1).trim();

        t = t.replace(".", "").replace(",", ".");
        try {
            BigDecimal v = new BigDecimal(t);
            return negativo ? v.negate() : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String texto(JsonNode no, String campo) {
        JsonNode v = no.get(campo);
        return v == null || v.isNull() ? null : v.asString();
    }
}
