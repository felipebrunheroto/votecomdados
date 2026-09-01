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

    public List<Long> todosOsIds() {
        return jdbc.sql("SELECT id FROM proposicao ORDER BY id")
            .query(Long.class).list();
    }

    private static Proposicao proposicao(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Proposicao(
            rs.getLong("id"),
            CasaLegislativa.valueOf(rs.getString("casa")),
            Esfera.valueOf(rs.getString("esfera")),
            rs.getString("sigla_tipo"),
            intOuNulo(rs, "numero"),
            rs.getInt("ano"),
            rs.getString("ementa"),
            textoArray(rs, "temas"),
            dataOuNula(rs, "data_apresentacao"),
            rs.getString("situacao_atual"),
            rs.getString("url_inteiro_teor"),
            rs.getString("url_tramitacao"));
    }
}
