package br.org.votecomdados.api.servico;

import static org.assertj.core.api.Assertions.assertThat;

import br.org.votecomdados.api.repositorio.RepositorioDeEmendasContador;
import br.org.votecomdados.core.dominio.Enums.LocalidadeEmenda;
import br.org.votecomdados.core.dominio.Modelo.Emenda;
import br.org.votecomdados.core.dominio.Modelo.FatiaDeLocalidade;
import br.org.votecomdados.core.dominio.Modelo.PeriodoCoberto;
import br.org.votecomdados.core.dominio.Modelo.ResumoDeEmendas;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * O custo do resumo nacional, contado em chamadas.
 *
 * <h2>Por que contar, e não cronometrar</h2>
 *
 * A primeira versão deste teste media o tempo do endpoint em lote com teto de
 * 5 segundos. <b>Ela passava com a regressão reintroduzida</b>: com doze
 * cidades semeadas, trinta e seis agregações sobre uma tabela minúscula ainda
 * são rápidas. Um teste que passa com o código quebrado não verifica nada.
 *
 * <p>O invariante de verdade é "uma vez por requisição, não uma por cidade",
 * e isso se afirma contando invocações — determinístico, e independente do
 * tamanho da semente.
 *
 * <h2>O que aconteceu em produção</h2>
 *
 * O resumo nacional era consultado dentro do resumo de cada cidade. Com uma
 * requisição por vez era só lento. No endpoint em lote, com 1.596 cidades,
 * viraram milhares de agregações sobre a tabela inteira e a API devolveu
 * <b>504 ao próprio build do site</b>.
 */
class ConsultaEmendasTest {

    private static final ResumoDeEmendas NACIONAL = new ResumoDeEmendas(
        4040, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE,
        List.of(new FatiaDeLocalidade(LocalidadeEmenda.MUNICIPIO, 4040, BigDecimal.ONE)),
        new PeriodoCoberto(2023, 2026), 1596);

    private static Emenda emenda(String uf, String municipio) {
        return new Emenda("2025418400" + municipio.length(), 2025, "Individual",
            "FULANO", null, null, municipio, uf, municipio + " - " + uf,
            LocalidadeEmenda.MUNICIPIO, BigDecimal.ONE, BigDecimal.ONE,
            BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE);
    }

    @Test
    void lote_pede_o_resumo_nacional_uma_vez_so() {
        var porCidade = new LinkedHashMap<String, List<Emenda>>();
        for (int i = 0; i < 200; i++) {
            porCidade.put("PE/CIDADE" + i, List.of(emenda("PE", "CIDADE" + i)));
        }
        var repositorio = new RepositorioDeEmendasContador(porCidade, NACIONAL);

        var resultado = new ConsultaEmendas(repositorio).todosOsMunicipios();

        assertThat(resultado).hasSize(200);
        assertThat(repositorio.chamadasAoNacional.get())
            .as("uma por cidade seria 200; o build levou 504 por causa disso")
            .isEqualTo(1);
    }

    @Test
    void cada_cidade_do_lote_carrega_o_resumo_nacional() {
        var porCidade = new LinkedHashMap<String, List<Emenda>>();
        porCidade.put("SP/SANTOS", List.of(emenda("SP", "SANTOS")));

        var uma = new ConsultaEmendas(new RepositorioDeEmendasContador(porCidade, NACIONAL))
            .todosOsMunicipios().getFirst();

        assertThat(uma.municipio()).isEqualTo("SANTOS");
        assertThat(uma.nacional()).as("a tela declara a lacuna a partir dele").isNotNull();
        assertThat(uma.resumo().periodo()).as("periodo vem do nacional, nao da cidade")
            .isEqualTo(new PeriodoCoberto(2023, 2026));
        assertThat(uma.resumo().municipiosComRegistro()).isEqualTo(1596);
    }
}
