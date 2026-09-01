package br.org.votecomdados.ingestion.mandato;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Converte o histórico da Casa — que é uma lista de <b>eventos</b> — em
 * <b>períodos</b>.
 *
 * <p>A conversão parece trivial e não é. O formato tem três armadilhas, todas
 * verificadas no dado real (ver {@code db/golden/README.md}), e cada uma
 * produziria um erro silencioso na derivação de ausência:
 *
 * <ol>
 *   <li><b>Eventos sem {@code situacao}</b> são metadados ("Nome no início da
 *       legislatura", "Alteração de partido" sem mudança de status). Tratá-los
 *       como transição criaria períodos fantasma e faria a mesma pessoa
 *       "entrar em exercício" várias vezes no mesmo mandato.</li>
 *   <li><b>A mesma data e hora se repete</b> — seis eventos em
 *       {@code 2023-02-01T00:00} no exemplo real. Sem ordenação estável, o
 *       resultado mudaria entre execuções, e o reprocesso deixaria de ser
 *       idempotente.</li>
 *   <li><b>Transições no mesmo dia</b> ({@code CONVOCADO} 14:56,
 *       {@code Exercício} 15:15). Como o período é em DATA, isso geraria um
 *       intervalo vazio — e o banco recusaria a linha, ou pior, aceitaria uma
 *       linha degenerada.</li>
 * </ol>
 *
 * <p>Regra adotada para a terceira: <b>vale a última situação do dia</b>. É a
 * leitura correta em granularidade de data — se o parlamentar foi convocado e
 * empossado no mesmo dia, ele estava em exercício naquele dia.
 */
@Component
public class ConstrutorDePeriodos {

    /**
     * @param eventos como a Casa devolve, em qualquer ordem
     * @return períodos contíguos, sem sobreposição, prontos para o
     *         {@code EXCLUDE} de {@code mandato_exercicio}
     */
    public List<PeriodoDeExercicio> construir(Iterable<JsonNode> eventos) {
        record Marco(LocalDateTime quando, int ordem, String situacao, String condicao) {}

        var marcos = new ArrayList<Marco>();
        int ordem = 0;
        for (JsonNode e : eventos) {
            String situacao = texto(e, "situacao");
            // Sem situação não há transição de status: é mudança de nome ou de
            // partido, que não interessa a este mapeamento.
            if (situacao == null) { ordem++; continue; }
            LocalDateTime quando = dataHora(texto(e, "dataHora"));
            if (quando == null) { ordem++; continue; }
            marcos.add(new Marco(quando, ordem++, situacao, texto(e, "condicaoEleitoral")));
        }

        // A ordem por índice original é o desempate: sem ela, eventos com a
        // mesma dataHora sairiam em ordem arbitrária.
        marcos.sort(Comparator.comparing(Marco::quando).thenComparingInt(Marco::ordem));

        // Um marco por DIA: vale o último: convocado e empossado no mesmo dia
        // significa em exercício naquele dia.
        var porDia = new ArrayList<Marco>();
        for (Marco m : marcos) {
            if (!porDia.isEmpty()
                && porDia.getLast().quando().toLocalDate().equals(m.quando().toLocalDate())) {
                porDia.set(porDia.size() - 1, m);
            } else {
                porDia.add(m);
            }
        }

        // Marcos consecutivos idênticos não abrem período novo. Mudança de
        // partido em pleno exercício gera evento de 'Exercício' repetido, e
        // fatiar o período nisso seria ruído sem significado.
        var distintos = new ArrayList<Marco>();
        for (Marco m : porDia) {
            if (!distintos.isEmpty()
                && distintos.getLast().situacao().equals(m.situacao())
                && java.util.Objects.equals(distintos.getLast().condicao(), m.condicao())) {
                continue;
            }
            distintos.add(m);
        }

        var periodos = new ArrayList<PeriodoDeExercicio>();
        for (int i = 0; i < distintos.size(); i++) {
            Marco m = distintos.get(i);
            LocalDate inicio = m.quando().toLocalDate();
            LocalDate fim = i + 1 < distintos.size()
                ? distintos.get(i + 1).quando().toLocalDate()
                : null;   // ainda vigente
            periodos.add(new PeriodoDeExercicio(m.situacao(), m.condicao(), inicio, fim));
        }
        return periodos;
    }

    private static String texto(JsonNode no, String campo) {
        JsonNode v = no.get(campo);
        if (v == null || v.isNull()) return null;
        String s = v.asString().trim();
        return s.isEmpty() ? null : s;
    }

    private static LocalDateTime dataHora(String s) {
        if (s == null) return null;
        try {
            return LocalDateTime.parse(s);
        } catch (DateTimeParseException e) {
            try {
                return LocalDate.parse(s).atStartOfDay();
            } catch (DateTimeParseException ignorado) {
                return null;
            }
        }
    }
}
