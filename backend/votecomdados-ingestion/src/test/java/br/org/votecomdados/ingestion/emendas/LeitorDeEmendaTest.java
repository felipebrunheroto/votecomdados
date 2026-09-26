package br.org.votecomdados.ingestion.emendas;

import static org.assertj.core.api.Assertions.assertThat;

import br.org.votecomdados.core.dominio.Enums.LocalidadeEmenda;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Os casos abaixo não são inventados: todos saíram das 6.311 emendas de 2025
 * baixadas no spike de 25/09/2026. Cada um representa uma forma real que a
 * fonte devolve, e três deles quebravam a primeira versão do leitor.
 */
class LeitorDeEmendaTest {

    // ---- valores ---------------------------------------------------------

    @Test
    void valor_em_formato_brasileiro() {
        assertThat(LeitorDeEmenda.valor("2.359.960,00")).isEqualByComparingTo("2359960.00");
        assertThat(LeitorDeEmenda.valor("12.599,10")).isEqualByComparingTo("12599.10");
        assertThat(LeitorDeEmenda.valor("0,00")).isEqualByComparingTo("0.00");
    }

    /**
     * O sinal vem separado do número por espaço. Apareceu na PRIMEIRA linha
     * real devolvida pela API (2025, página 1, LUIS CARLOS HEINZE).
     */
    @Test
    void negativo_tem_o_sinal_separado_por_espaco() {
        assertThat(LeitorDeEmenda.valor("- 26.002,00")).isEqualByComparingTo("-26002.00");
        assertThat(LeitorDeEmenda.valor("-26.002,00")).isEqualByComparingTo("-26002.00");
    }

    /**
     * Zero indistinguível de ausência é o número errado que esta plataforma
     * não pode publicar: um estorno de R$ 26 mil viraria "nada aconteceu".
     */
    @Test
    void valor_ilegivel_vira_ausencia_e_nunca_zero() {
        assertThat(LeitorDeEmenda.valor("")).isNull();
        assertThat(LeitorDeEmenda.valor("   ")).isNull();
        assertThat(LeitorDeEmenda.valor("nao se aplica")).isNull();
        assertThat(LeitorDeEmenda.valor(null)).isNull();
        // e o zero de verdade continua sendo zero, não ausência
        assertThat(LeitorDeEmenda.valor("0,00")).isNotNull().isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ---- localidade ------------------------------------------------------

    @Test
    void as_quatro_formas_de_localidade() {
        assertThat(LeitorDeEmenda.classificar("ITAMARAJU - BA")).isEqualTo(LocalidadeEmenda.MUNICIPIO);
        assertThat(LeitorDeEmenda.classificar("BAHIA (UF)")).isEqualTo(LocalidadeEmenda.ESTADO);
        assertThat(LeitorDeEmenda.classificar("Nacional")).isEqualTo(LocalidadeEmenda.NACIONAL);
        assertThat(LeitorDeEmenda.classificar("MÚLTIPLO")).isEqualTo(LocalidadeEmenda.MULTIPLO);
        assertThat(LeitorDeEmenda.classificar("Múltiplo")).isEqualTo(LocalidadeEmenda.MULTIPLO);
    }

    @Test
    void localidade_desconhecida_nao_vira_municipio_por_engano() {
        assertThat(LeitorDeEmenda.classificar("")).isEqualTo(LocalidadeEmenda.OUTRO);
        assertThat(LeitorDeEmenda.classificar(null)).isEqualTo(LocalidadeEmenda.OUTRO);
        assertThat(LeitorDeEmenda.classificar("EXTERIOR")).isEqualTo(LocalidadeEmenda.OUTRO);
    }

    /**
     * Cidade composta e com hífen no próprio nome: o separador é " - " com
     * espaços, e "BIRITIBA-MIRIM - SP" existe de verdade na base de 2025.
     */
    @Test
    void municipio_com_hifen_no_nome() {
        assertThat(LeitorDeEmenda.classificar("BIRITIBA-MIRIM - SP")).isEqualTo(LocalidadeEmenda.MUNICIPIO);
        assertThat(LeitorDeEmenda.municipio("BIRITIBA-MIRIM - SP")).isEqualTo("BIRITIBA-MIRIM");
        assertThat(LeitorDeEmenda.uf("BIRITIBA-MIRIM - SP")).isEqualTo("SP");
        assertThat(LeitorDeEmenda.municipio("SANTA RITA DO PASSA QUATRO - SP"))
            .isEqualTo("SANTA RITA DO PASSA QUATRO");
    }

    @Test
    void cidade_e_uf_so_saem_de_localidade_de_municipio() {
        assertThat(LeitorDeEmenda.municipio("BAHIA (UF)")).isNull();
        assertThat(LeitorDeEmenda.uf("Nacional")).isNull();
        assertThat(LeitorDeEmenda.municipio("MÚLTIPLO")).isNull();
    }

    // ---- autoria ---------------------------------------------------------

    /**
     * 110 linhas de 2025 (1,7%, 14 parlamentares, R$ 473 mi) chegam assim. O
     * nome cru não casa com ninguém na nossa base, então sem este parse o
     * vínculo dessas emendas se perde inteiro.
     */
    @Test
    void autoria_transferida_separa_quem_tem_hoje_de_quem_veio() {
        String cru = "ALINE GURGEL (EX-PARLAMENTAR JOSE AUGUSTO PUPPIO, NOS TERMOS "
            + "ART. 78 LDO 2025 E DA MENSAGEM 95-CN, DE 06.11.25)";
        assertThat(LeitorDeEmenda.autorNome(cru)).isEqualTo("ALINE GURGEL");
        assertThat(LeitorDeEmenda.autorOrigemNome(cru)).isEqualTo("JOSE AUGUSTO PUPPIO");
    }

    @Test
    void autoria_normal_nao_inventa_origem() {
        assertThat(LeitorDeEmenda.autorNome("LUIS CARLOS HEINZE")).isEqualTo("LUIS CARLOS HEINZE");
        assertThat(LeitorDeEmenda.autorOrigemNome("LUIS CARLOS HEINZE")).isNull();
    }

    // ---- código do autor -------------------------------------------------

    /** "202541840004" = ano 2025 + autor 4184 + número 0004. */
    @Test
    void codigo_do_autor_sai_do_codigo_da_emenda() {
        assertThat(LeitorDeEmenda.codigoDoAutor("202541840004", "0004")).isEqualTo("4184");
        assertThat(LeitorDeEmenda.codigoDoAutor("202541440002", "0002")).isEqualTo("4144");
    }

    /**
     * Se o número não confere com os quatro últimos dígitos, a leitura da
     * posição está errada — e âncora de identidade errada é pior que
     * nenhuma: ela une pessoas diferentes em silêncio.
     */
    @Test
    void codigo_sem_conferencia_com_o_numero_e_recusado() {
        assertThat(LeitorDeEmenda.codigoDoAutor("202541840004", "0009")).isNull();
        assertThat(LeitorDeEmenda.codigoDoAutor("2025418400", "0004")).isNull();
        assertThat(LeitorDeEmenda.codigoDoAutor("20254184000X", "0004")).isNull();
        assertThat(LeitorDeEmenda.codigoDoAutor(null, "0004")).isNull();
    }
}
