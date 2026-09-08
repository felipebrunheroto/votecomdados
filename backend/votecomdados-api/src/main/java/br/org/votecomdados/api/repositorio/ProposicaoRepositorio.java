package br.org.votecomdados.api.repositorio;

import br.org.votecomdados.core.dominio.Enums.*;
import br.org.votecomdados.core.dominio.Modelo.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import static br.org.votecomdados.api.repositorio.MapeadoresSql.*;

@Repository
public class ProposicaoRepositorio {

    /**
     * A eleição que o projeto cobre. Mesma constante conceitual de
     * {@code JobDeCoorte.ANO_DA_COORTE}: o site é sobre quem se apresenta ao
     * eleitorado em 2026, e o que esse mandato produziu.
     */
    private static final int ANO_DA_LEGISLATURA_CORRENTE = 2026;

    private final JdbcClient jdbc;

    ProposicaoRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Temas vêm agregados em array para evitar N+1 na listagem. */
    private static final String COLUNAS = """
        SELECT p.id, p.casa::text AS casa, p.sigla_tipo, p.numero, p.ano, p.ementa,
               p.data_apresentacao, p.situacao_atual, p.url_inteiro_teor, p.url_tramitacao,
               CASE p.casa WHEN 'ALESP' THEN 'ESTADUAL' ELSE 'FEDERAL' END AS esfera,
               coalesce(array_agg(t.tema ORDER BY t.tema)
                        FILTER (WHERE t.tema IS NOT NULL), '{}') AS temas
          FROM proposicao p
          LEFT JOIN proposicao_tema t ON t.proposicao_id = p.id
        """;

    public List<Proposicao> doPolitico(UUID politicoId, int limite, int offset) {
        return jdbc.sql(COLUNAS + """
             JOIN proposicao_autor a ON a.proposicao_id = p.id
            WHERE a.politico_id = :id
            GROUP BY p.id
            ORDER BY p.data_apresentacao DESC NULLS LAST, p.id DESC
            LIMIT :limite OFFSET :offset
            """)
            .param("id", politicoId).param("limite", limite).param("offset", offset)
            .query((rs, n) -> proposicao(rs))
            .list();
    }

    public long contarDoPolitico(UUID politicoId) {
        return jdbc.sql("""
            SELECT count(*) FROM proposicao_autor WHERE politico_id = :id
            """)
            .param("id", politicoId)
            .query(Long.class).single();
    }

    public Optional<ProposicaoDetalhe> porId(long id) {
        var base = jdbc.sql(COLUNAS + " WHERE p.id = :id GROUP BY p.id")
            .param("id", id)
            .query((rs, n) -> proposicao(rs))
            .optional();

        return base.map(p -> new ProposicaoDetalhe(
            p.id(), p.casa(), p.esfera(), p.siglaTipo(), p.numero(), p.ano(), p.ementa(),
            p.temas(), p.dataApresentacao(), p.situacaoAtual(), p.urlInteiroTeor(),
            p.urlTramitacao(), autores(id)));
    }

    /**
     * Autoria completa, inclusive de quem não é candidato em 2026.
     *
     * `politico_id` nulo significa coautor fora da coorte: aparece pelo nome,
     * sem perfil. Omitir esses nomes distorceria o registro da matéria.
     */
    public List<AutorProposicao> autores(long proposicaoId) {
        return jdbc.sql("""
            SELECT politico_id, autor_nome, autor_principal
              FROM proposicao_autor
             WHERE proposicao_id = :id
             ORDER BY autor_principal DESC, autor_nome
            """)
            .param("id", proposicaoId)
            .query((rs, n) -> new AutorProposicao(
                rs.getString("politico_id") == null
                    ? null : UUID.fromString(rs.getString("politico_id")),
                rs.getString("autor_nome"),
                rs.getBoolean("autor_principal")))
            .list();
    }

    /**
     * Os ids que o site pré-renderiza — {@code generateStaticParams} consome
     * daqui.
     *
     * <p><b>Recorte pela legislatura corrente.</b> Em 08/09/2026 o primeiro
     * ciclo incremental completo levou a base a 346.481 proposições, e o build
     * do site estourou a pilha do Next tentando gerar página para cada uma.
     * Mesmo depois do recorte de coorte na Alesp sobrariam ~209 mil -- contra
     * 79 mil que já levavam cerca de uma hora.
     *
     * <p>O corte NÃO esconde nada: matéria mais antiga continua na API, no
     * pacote de dados abertos e no site, renderizada no navegador pelo
     * fallback de {@code not-found.tsx} (o CloudFront reescreve 404 para
     * {@code /404.html} com status 200). O que ela perde é a página pronta no
     * HTML -- ou seja, indexação em buscador, não acesso.
     */
    public List<Long> todosOsIds() {
        return jdbc.sql("""
                SELECT id FROM proposicao
                 -- Peça sem ano (parecer, emenda) entra pela data de
                 -- apresentação: excluí-la por não ter ano na designação
                 -- deixaria de fora 16.902 peças do mandato corrente.
                 WHERE coalesce(ano, extract(year FROM data_apresentacao)) >= :desde
                 ORDER BY id
                """)
            .param("desde", ANO_DA_LEGISLATURA_CORRENTE)
            .query(Long.class).list();
    }

    private static Proposicao proposicao(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Proposicao(
            rs.getLong("id"),
            CasaLegislativa.valueOf(rs.getString("casa")),
            Esfera.valueOf(rs.getString("esfera")),
            rs.getString("sigla_tipo"),
            intOuNulo(rs, "numero"),
            // getObject num smallint devolve Short: ler assim mantem o tipo
            // e ainda distingue "sem ano" de zero.
            rs.getObject("ano") == null ? null : rs.getInt("ano"),
            rs.getString("ementa"),
            textoArray(rs, "temas"),
            dataOuNula(rs, "data_apresentacao"),
            rs.getString("situacao_atual"),
            rs.getString("url_inteiro_teor"),
            rs.getString("url_tramitacao"));
    }
}
