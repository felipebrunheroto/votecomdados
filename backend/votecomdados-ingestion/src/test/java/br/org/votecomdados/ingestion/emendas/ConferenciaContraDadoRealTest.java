package br.org.votecomdados.ingestion.emendas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import br.org.votecomdados.core.dominio.Enums.LocalidadeEmenda;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Confere o leitor contra as 6.311 emendas reais de 2025, exigindo que ele
 * reproduza, ao centavo, os números que a análise em Python do spike produziu.
 *
 * <h2>Por que vale mais que os testes unitários ao lado</h2>
 *
 * São duas implementações independentes chegando ao mesmo resultado sobre o
 * mesmo dado. Um teste unitário confirma que o leitor faz o que eu achei que
 * ele devia fazer; este confirma que ele faz o que a fonte realmente exige,
 * em 6.311 linhas que eu não escolhi.
 *
 * <p>Os 628 nomes distintos são a asserção mais fina: sem o parse da anotação
 * de autoria transferida seriam 642, porque os 14 parlamentares que receberam
 * emenda de outro contariam duas vezes.
 *
 * <h2>É PULADO no CI, de propósito</h2>
 *
 * A amostra é dado bruto de terceiro e não vai para o repositório. Para rodar
 * localmente:
 *
 * <pre>
 *   export PORTAL_TRANSPARENCIA_TOKEN=...
 *   python3 tools/spike-emendas.py --ano 2025
 *   mkdir -p backend/votecomdados-ingestion/target/amostra
 *   cp emendas-2025.json backend/votecomdados-ingestion/target/amostra/
 * </pre>
 *
 * Sem o arquivo o teste é ignorado, e não falso-positivo: {@code assumeTrue}
 * marca como pulado, não como passado.
 */
class ConferenciaContraDadoRealTest {

    @Test
    void reproduz_os_numeros_medidos_no_spike() throws Exception {
        Path arquivo = Path.of("target/amostra/emendas-2025.json");
        assumeTrue(Files.exists(arquivo),
            "amostra real ausente em target/amostra/emendas-2025.json "
            + "— ver o javadoc desta classe para gerar");

        JsonNode linhas = new ObjectMapper().readTree(Files.readString(arquivo));
        Map<LocalidadeEmenda, BigDecimal> porForma = new EnumMap<>(LocalidadeEmenda.class);
        Set<String> codigos = new HashSet<>();
        Set<String> nomes = new HashSet<>();
        int comCodigo = 0, transferidas = 0;
        BigDecimal pago = BigDecimal.ZERO, restoPago = BigDecimal.ZERO;

        for (JsonNode l : linhas) {
            String bruto = l.get("nomeAutor").asString();
            String cod = LeitorDeEmenda.codigoDoAutor(
                l.get("codigoEmenda").asString(), l.get("numeroEmenda").asString());
            if (cod != null) { comCodigo++; codigos.add(cod); nomes.add(LeitorDeEmenda.autorNome(bruto)); }
            if (LeitorDeEmenda.autorOrigemNome(bruto) != null) transferidas++;

            BigDecimal p = LeitorDeEmenda.valor(l.get("valorPago").asString());
            BigDecimal r = LeitorDeEmenda.valor(l.get("valorRestoPago").asString());
            if (p != null) pago = pago.add(p);
            if (r != null) restoPago = restoPago.add(r);
            if (p != null) {
                LocalidadeEmenda f = LeitorDeEmenda.classificar(l.get("localidadeDoGasto").asString());
                porForma.merge(f, p, BigDecimal::add);
            }
        }

        System.out.printf("linhas=%d codigoOk=%d codigos=%d nomes=%d transferidas=%d%n",
            linhas.size(), comCodigo, codigos.size(), nomes.size(), transferidas);
        System.out.printf("pago=%.2f restoPago=%.2f%n", pago, restoPago);
        porForma.forEach((f, v) -> System.out.printf("  %-10s %.2f%n", f, v));

        assertThat(linhas.size()).isEqualTo(6311);
        assertThat(comCodigo).as("codigoEmenda = ano+autor+numero em TODAS").isEqualTo(6311);
        assertThat(codigos).as("codigos de autor distintos").hasSize(628);
        assertThat(nomes).as("nomes distintos, depois de limpar a anotacao").hasSize(628);
        assertThat(transferidas).as("linhas de autoria transferida").isEqualTo(110);
        assertThat(pago).isEqualByComparingTo("32479836877.66");
        assertThat(restoPago).isEqualByComparingTo("6092630334.88");
        assertThat(porForma.get(LocalidadeEmenda.MUNICIPIO)).isEqualByComparingTo("1119532581.01");
        assertThat(porForma.get(LocalidadeEmenda.MULTIPLO)).isEqualByComparingTo("28729068934.76");
    }
}
