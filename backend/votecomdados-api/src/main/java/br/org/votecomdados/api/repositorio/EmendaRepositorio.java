package br.org.votecomdados.api.repositorio;

import br.org.votecomdados.core.dominio.Enums.LocalidadeEmenda;
import br.org.votecomdados.core.dominio.Modelo.Emenda;
import br.org.votecomdados.core.dominio.Modelo.FatiaDeLocalidade;
import br.org.votecomdados.core.dominio.Modelo.PeriodoCoberto;
import br.org.votecomdados.core.dominio.Modelo.ResumoDeEmendas;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Leitura de emendas.
 *
 * <h2>Toda soma inclui restos a pagar</h2>
 *
 * {@code valor_pago} sozinho responde por 84,2% do desembolso: em 2025 saíram
 * R$ 32,5 bi por ele e R$ 6,1 bi por restos. Somar só o primeiro faria 43 das
 * 474 cidades com emenda identificada aparecerem com R$ 0,00 tendo recebido —
 * Santos entre elas, com R$ 600 mil. Por isso {@code desembolso} é calculado
 * aqui, no servidor, e não deixado para quem consome decidir.
 *
 * <p>{@code coalesce} em cada valor porque as colunas são nuláveis de
 * propósito: valor ilegível na fonte vira ausência, nunca zero. Na soma,
 * ausência precisa contar como zero sem apagar a linha inteira.
 */
@Repository
public class EmendaRepositorio {

    private static final String COLUNAS = """
            e.codigo, e.ano, e.tipo, e.autor_nome, e.politico_id,
            e.autor_origem_nome, e.municipio_nome, e.uf,
            e.localidade_bruta, e.localidade_tipo::text AS localidade_tipo,
            e.valor_empenhado, e.valor_liquidado, e.valor_pago,
            e.valor_resto_pago,
            coalesce(e.valor_pago, 0) + coalesce(e.valor_resto_pago, 0) AS desembolso
        """;

    private final JdbcClient jdbc;

    EmendaRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Emenda linha(java.sql.ResultSet r, int i) throws java.sql.SQLException {
        return new Emenda(
            r.getString("codigo"), r.getInt("ano"), r.getString("tipo"),
            r.getString("autor_nome"),
            r.getObject("politico_id", UUID.class),
            r.getString("autor_origem_nome"),
            r.getString("municipio_nome"), r.getString("uf"),
            r.getString("localidade_bruta"),
            LocalidadeEmenda.valueOf(r.getString("localidade_tipo")),
            r.getBigDecimal("valor_empenhado"), r.getBigDecimal("valor_liquidado"),
            r.getBigDecimal("valor_pago"), r.getBigDecimal("valor_resto_pago"),
            r.getBigDecimal("desembolso"));
    }

    public List<Emenda> doPolitico(UUID politicoId, int limite, int deslocamento) {
        return jdbc.sql("SELECT " + COLUNAS + """
                  FROM emenda e
                 WHERE e.politico_id = :politico
                 ORDER BY e.ano DESC,
                          -- Quem tem cidade primeiro: é a informação que a
                          -- pessoa veio procurar, e o resto é declarado no
                          -- resumo em vez de empurrar o útil para baixo.
                          (e.localidade_tipo = 'MUNICIPIO') DESC,
                          coalesce(e.valor_pago, 0) + coalesce(e.valor_resto_pago, 0) DESC,
                          e.codigo
                 LIMIT :limite OFFSET :deslocamento
                """)
            .param("politico", politicoId)
            .param("limite", limite).param("deslocamento", deslocamento)
            .query(EmendaRepositorio::linha).list();
    }

    public int contarDoPolitico(UUID politicoId) {
        return jdbc.sql("SELECT count(*) FROM emenda WHERE politico_id = :p")
            .param("p", politicoId).query(Integer.class).single();
    }

    /**
     * O que uma cidade recebeu.
     *
     * <p>Compara sem acento e sem caixa porque o nome vem da CGU como texto
     * livre e quem consulta digita como quiser — não há código IBGE nem na
     * fonte nem no nosso schema.
     */
    public List<Emenda> doMunicipio(String uf, String municipio) {
        return jdbc.sql("SELECT " + COLUNAS + """
                  FROM emenda e
                 WHERE e.localidade_tipo = 'MUNICIPIO'
                   AND e.uf = upper(:uf)
                   AND unaccent_imutavel(upper(e.municipio_nome))
                     = unaccent_imutavel(upper(:municipio))
                 ORDER BY e.ano DESC,
                          coalesce(e.valor_pago, 0) + coalesce(e.valor_resto_pago, 0) DESC,
                          e.codigo
                """)
            .param("uf", uf).param("municipio", municipio)
            .query(EmendaRepositorio::linha).list();
    }

    /**
     * As cidades que têm ao menos uma emenda identificada.
     *
     * <p>Sem paginação, de propósito: alimenta {@code generateStaticParams} no
     * build do frontend, como {@code /proposicoes} já faz. São 474 das 5.570 —
     * as outras chegam pelo fallback de cliente, e pré-renderizar 5.096
     * páginas idênticas de "nenhuma emenda identificada" custaria objetos em
     * toda publicação sem ter o que indexar.
     */
    public List<Municipio> municipiosComEmenda() {
        return jdbc.sql("""
                SELECT DISTINCT uf, municipio_nome
                  FROM emenda
                 WHERE localidade_tipo = 'MUNICIPIO'
                 ORDER BY uf, municipio_nome
                """)
            .query((r, i) -> new Municipio(r.getString("uf"), r.getString("municipio_nome")))
            .list();
    }

    public record Municipio(String uf, String municipio) {}

    /**
     * Anos que a base cobre — o menor e o maior com alguma emenda.
     *
     * <p>É propriedade da NOSSA cobertura, não do recorte consultado. Derivar
     * o período das linhas exibidas diria "2025" para um parlamentar que só
     * teve emenda naquele ano, sugerindo que é só disso que dispomos; e diria
     * períodos diferentes em páginas diferentes, para o mesmo acervo.
     *
     * <p>Sem este rótulo a tela dizia "R$ 68.149.687 desembolsados no total",
     * e "no total" era lido como "em geral" quando significava "em 2025".
     * Omissão que engana é o que esta plataforma existe para não fazer.
     */
    public int[] periodoCoberto() {
        var r = jdbc.sql("SELECT min(ano) AS ini, max(ano) AS fim FROM emenda")
            .query().singleRow();
        Object ini = r.get("ini");
        if (ini == null) return null;
        return new int[] { ((Number) ini).intValue(), ((Number) r.get("fim")).intValue() };
    }

    /** Quantos parlamentares distintos destinaram emenda à cidade. */
    public int parlamentaresDoMunicipio(String uf, String municipio) {
        return jdbc.sql("""
                SELECT count(DISTINCT coalesce(e.politico_id::text, e.codigo_autor))
                  FROM emenda e
                 WHERE e.localidade_tipo = 'MUNICIPIO'
                   AND e.uf = upper(:uf)
                   AND unaccent_imutavel(upper(e.municipio_nome))
                     = unaccent_imutavel(upper(:municipio))
                """)
            .param("uf", uf).param("municipio", municipio)
            .query(Integer.class).single();
    }

    /**
     * Resumo de um político, incluindo o que NÃO tem município.
     *
     * <p>É o número que permite à interface declarar a lacuna. Sem ele, uma
     * página mostraria quatro emendas de cidade e calaria sobre as quarenta
     * restantes — parecendo completa.
     */
    public ResumoDeEmendas resumoDoPolitico(UUID politicoId) {
        return resumo("e.politico_id = :chave", politicoId);
    }

    /**
     * O {@code " "} antes de {@code condicao} não é enfeite: <b>text block do
     * Java remove espaço em branco no fim de cada linha</b>, então
     * {@code WHERE """ + condicao} produz {@code WHEREe.politico_id} e o
     * Postgres recusa com "syntax error at or near". Só aparece em execução.
     */
    private ResumoDeEmendas resumo(String condicao, Object chave) {
        var totais = jdbc.sql("""
                SELECT count(*) AS linhas,
                       coalesce(sum(e.valor_empenhado), 0) AS empenhado,
                       coalesce(sum(e.valor_pago), 0) AS pago,
                       coalesce(sum(e.valor_resto_pago), 0) AS resto,
                       coalesce(sum(coalesce(e.valor_pago, 0)
                                  + coalesce(e.valor_resto_pago, 0)), 0) AS desembolso
                  FROM emenda e WHERE """ + " " + condicao)
            .param("chave", chave).query().singleRow();

        List<FatiaDeLocalidade> fatias = jdbc.sql("""
                SELECT e.localidade_tipo::text AS tipo, count(*) AS linhas,
                       coalesce(sum(coalesce(e.valor_pago, 0)
                                  + coalesce(e.valor_resto_pago, 0)), 0) AS desembolso
                  FROM emenda e WHERE """ + " " + condicao + """
                 GROUP BY e.localidade_tipo ORDER BY 3 DESC
                """)
            .param("chave", chave)
            .query((r, i) -> new FatiaDeLocalidade(
                LocalidadeEmenda.valueOf(r.getString("tipo")),
                r.getInt("linhas"), r.getBigDecimal("desembolso")))
            .list();

        int[] p = periodoCoberto();
        return new ResumoDeEmendas(
            ((Number) totais.get("linhas")).intValue(),
            (BigDecimal) totais.get("empenhado"),
            (BigDecimal) totais.get("pago"),
            (BigDecimal) totais.get("resto"),
            (BigDecimal) totais.get("desembolso"),
            fatias,
            p == null ? null : new PeriodoCoberto(p[0], p[1]));
    }
}
