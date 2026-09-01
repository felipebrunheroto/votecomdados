package br.org.votecomdados.ingestion.coorte;

import br.org.votecomdados.core.dominio.Enums.Cargo;
import br.org.votecomdados.core.dominio.Enums.Esfera;
import br.org.votecomdados.core.dominio.Enums.StatusCandidatura;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Traduz uma linha de {@code consulta_cand} do TSE para o modelo do domínio.
 *
 * <h2>Layout verificado em 31/08/2026</h2>
 *
 * Conferido contra {@code consulta_cand_2026.zip}: 50 colunas, 29 arquivos por
 * UF, codificação <b>latin-1</b>, 20.809 candidaturas. A verificação corrigiu
 * dois erros que teriam aparecido na tela do eleitor:
 *
 * <ul>
 *   <li>os sentinelas são {@code #NE} e {@code #NULO}, <b>sem</b> o {@code #}
 *       final que este código presumia. Com a grafia errada, o sentinela
 *       passava direto e {@code DS_SIT_TOT_TURNO} virava "não eleito" — para
 *       uma eleição que ainda não ocorreu;</li>
 *   <li>{@code DS_SITUACAO_CANDIDATURA} vem {@code #NE} em 100% dos registros
 *       (o registro está sendo julgado), e traduzir isso para {@code APTO}
 *       seria afirmar em nome do TSE.</li>
 * </ul>
 */
@Component
public class LeitorDeCandidaturasTse {

    private static final DateTimeFormatter DATA_TSE =
        DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.of("pt", "BR"));

    /** Códigos de cargo do TSE. O código é estável; a descrição varia. */
    private static final Map<String, Cargo> CARGOS = Map.ofEntries(
        Map.entry("1", Cargo.PRESIDENTE),
        Map.entry("2", Cargo.VICE_PRESIDENTE),
        Map.entry("3", Cargo.GOVERNADOR),
        Map.entry("4", Cargo.VICE_GOVERNADOR),
        Map.entry("5", Cargo.SENADOR),
        Map.entry("6", Cargo.DEPUTADO_FEDERAL),
        Map.entry("7", Cargo.DEPUTADO_ESTADUAL),
        Map.entry("8", Cargo.DEPUTADO_DISTRITAL),
        Map.entry("9", Cargo.PRIMEIRO_SUPLENTE),
        Map.entry("10", Cargo.SEGUNDO_SUPLENTE),
        Map.entry("11", Cargo.PREFEITO),
        Map.entry("12", Cargo.VICE_PREFEITO),
        Map.entry("13", Cargo.VEREADOR)
    );

    private final CalculadoraDeHmac hmac;

    LeitorDeCandidaturasTse(CalculadoraDeHmac hmac) {
        this.hmac = hmac;
    }

    /**
     * @param linha registro cru, ainda COM o CPF — é o último ponto em que ele
     *              existe: sai daqui já convertido em HMAC
     * @return vazio quando a linha não é aproveitável (cargo desconhecido);
     *         o chamador manda para quarentena em vez de descartar
     */
    public Optional<CandidaturaDoTse> ler(JsonNode linha) {
        Cargo cargo = CARGOS.get(texto(linha, "CD_CARGO"));
        if (cargo == null) return Optional.empty();

        String uf = texto(linha, "SG_UF");
        Esfera esfera = esferaDe(cargo);

        return Optional.of(new CandidaturaDoTse(
            texto(linha, "SQ_CANDIDATO"),
            inteiro(linha, "ANO_ELEICAO", 0),
            inteiro(linha, "NR_TURNO", 1),
            cargo,
            esfera,
            uf == null ? "BR" : uf,
            esfera == Esfera.MUNICIPAL ? texto(linha, "NM_UE") : null,
            esfera == Esfera.MUNICIPAL ? texto(linha, "SG_UE") : null,
            texto(linha, "NM_CANDIDATO"),
            texto(linha, "NM_URNA_CANDIDATO"),
            // Único ponto do sistema em que o CPF é lido — e ele não sai daqui.
            hmac.hmacDe(texto(linha, "NR_CPF_CANDIDATO")),
            data(linha, "DT_NASCIMENTO"),
            texto(linha, "DS_GENERO"),
            texto(linha, "SG_PARTIDO"),
            inteiroOuNulo(linha, "NR_PARTIDO"),
            inteiroOuNulo(linha, "NR_CANDIDATO"),
            statusDe(texto(linha, "DS_SITUACAO_CANDIDATURA")),
            eleitoDe(texto(linha, "DS_SIT_TOT_TURNO"))));
    }

    private static Esfera esferaDe(Cargo cargo) {
        return switch (cargo) {
            case PRESIDENTE, VICE_PRESIDENTE, SENADOR, PRIMEIRO_SUPLENTE,
                 SEGUNDO_SUPLENTE, DEPUTADO_FEDERAL -> Esfera.FEDERAL;
            case GOVERNADOR, VICE_GOVERNADOR, DEPUTADO_ESTADUAL,
                 DEPUTADO_DISTRITAL -> Esfera.ESTADUAL;
            case PREFEITO, VICE_PREFEITO, VEREADOR -> Esfera.MUNICIPAL;
        };
    }

    /**
     * Entram TODOS os status, inclusive indeferido e sub judice — omitir quem
     * está em disputa judicial faria a plataforma parecer estar escondendo um
     * candidato, e o andamento do registro é informação pública de interesse.
     */
    private static StatusCandidatura statusDe(String descricao) {
        // Sentinela já virou null em texto(). Aqui, null significa que a fonte
        // não declarou situação — e inventar 'APTO' seria falar pelo TSE.
        if (descricao == null) return StatusCandidatura.NAO_INFORMADO;
        String d = descricao.toUpperCase(Locale.ROOT);
        if (d.contains("INDEFERIDO")) return StatusCandidatura.INDEFERIDO;
        if (d.contains("CASSAD")) return StatusCandidatura.CASSADO;
        if (d.contains("RENÚNCIA") || d.contains("RENUNCIA")) return StatusCandidatura.RENUNCIA;
        if (d.contains("DEFERIDO")) return StatusCandidatura.DEFERIDO;
        if (d.contains("INAPTO")) return StatusCandidatura.INAPTO;
        if (d.contains("APTO")) return StatusCandidatura.APTO;
        return StatusCandidatura.NAO_INFORMADO;
    }

    /**
     * {@code null} quando a eleição ainda não ocorreu — que é o caso de 2026 na
     * carga inicial. Falso e "ainda não se sabe" são coisas diferentes, e
     * gravar false diria ao leitor que a pessoa perdeu.
     */
    private static Boolean eleitoDe(String situacaoTotal) {
        // texto() já converteu o sentinela em null. Chegar aqui com null
        // significa "a eleição ainda não ocorreu" — e é o caso de 100% das
        // candidaturas de 2026 hoje.
        if (situacaoTotal == null || situacaoTotal.isBlank()) return null;
        String s = situacaoTotal.toUpperCase(Locale.ROOT);
        if (s.contains("NÃO INFORMADO") || s.contains("NAO INFORMADO")) return null;
        return s.contains("ELEITO") && !s.contains("NÃO ELEITO") && !s.contains("NAO ELEITO");
    }

    /**
     * Sentinelas do TSE: {@code #NE} (não especificado) e {@code #NULO}.
     *
     * <p>Verificado no arquivo real: vêm <b>sem</b> {@code #} final. As formas
     * com {@code #} ficam na lista porque aparecem em datasets mais antigos, e
     * deixar as duas custa nada. A grafia errada aqui não quebrava nada de
     * forma visível — apenas fazia o sentinela ser lido como texto, e virar
     * "não eleito" e "apto" mais adiante.
     */
    private static final java.util.Set<String> SENTINELAS =
        java.util.Set.of("#NE", "#NULO", "#NE#", "#NULO#", "#NULO#NULO#");

    private static String texto(JsonNode no, String campo) {
        JsonNode v = no.get(campo);
        if (v == null || v.isNull()) return null;
        String s = v.asString().trim();
        if (s.isEmpty() || SENTINELAS.contains(s)) return null;
        return s;
    }

    private static int inteiro(JsonNode no, String campo, int padrao) {
        Integer v = inteiroOuNulo(no, campo);
        return v == null ? padrao : v;
    }

    private static Integer inteiroOuNulo(JsonNode no, String campo) {
        String s = texto(no, campo);
        if (s == null) return null;
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate data(JsonNode no, String campo) {
        String s = texto(no, campo);
        if (s == null) return null;
        try {
            return LocalDate.parse(s, DATA_TSE);
        } catch (java.time.format.DateTimeParseException e) {
            try {
                return LocalDate.parse(s);   // ISO, caso a fonte mude de formato
            } catch (java.time.format.DateTimeParseException ignorado) {
                return null;
            }
        }
    }
}
