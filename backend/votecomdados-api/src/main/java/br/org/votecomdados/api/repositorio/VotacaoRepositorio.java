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
public class VotacaoRepositorio {

    private final JdbcClient jdbc;

    VotacaoRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Votos de um parlamentar.
     *
     * A nota metodológica vem de `mapeamento_voto`, versionada como dado: é ela
     * que explica ao leitor que "obstrução" não é posição sobre o mérito. Sai
     * pela mesma consulta para que a UI nunca exiba o voto sem o contexto.
     */
    public List<VotacaoDoPolitico> doPolitico(UUID politicoId, int limite, int offset) {
        return jdbc.sql("""
            SELECT v.id, v.data_votacao, v.descricao, v.casa::text AS casa,
                   v.ambito::text AS ambito, v.tipo::text AS tipo, v.secreta,
                   v.aprovada, v.url_fonte,
                   CASE v.casa WHEN 'ALESP' THEN 'ESTADUAL' ELSE 'FEDERAL' END AS esfera,
                   vn.voto::text AS voto, vn.voto_origem,
                   vn.origem_registro::text AS origem_registro, m.observacao AS nota,
                   coalesce(array_agg(t.tema ORDER BY t.tema)
                            FILTER (WHERE t.tema IS NOT NULL), '{}') AS temas
              FROM voto_nominal vn
              JOIN votacao v ON v.id = vn.votacao_id
              LEFT JOIN proposicao_tema t ON t.proposicao_id = v.proposicao_id
              -- A chave de tradução é o CÓDIGO quando a fonte publica um
              -- (só a Alesp, em voto_origem_codigo) e o texto quando não.
              -- Sem o coalesce, todo voto da Alesp perderia a nota
              -- metodológica: os 477 textos livres dela não são chave de
              -- mapeamento_voto, e nunca casariam.
              LEFT JOIN mapeamento_voto m
                     ON m.valor_origem = coalesce(vn.voto_origem_codigo, vn.voto_origem)
                    AND m.fonte::text = CASE v.casa WHEN 'ALESP' THEN 'ALESP'
                                                    WHEN 'SENADO' THEN 'SENADO'
                                                    ELSE 'CAMARA' END
             WHERE vn.politico_id = :id
             GROUP BY v.id, vn.voto, vn.voto_origem, vn.origem_registro, m.observacao
             ORDER BY v.data_votacao DESC
             LIMIT :limite OFFSET :offset
            """)
            .param("id", politicoId).param("limite", limite).param("offset", offset)
            .query((rs, n) -> new VotacaoDoPolitico(
                rs.getLong("id"),
                rs.getTimestamp("data_votacao").toInstant(),
                rs.getString("descricao"),
                CasaLegislativa.valueOf(rs.getString("casa")),
                Esfera.valueOf(rs.getString("esfera")),
                AmbitoVotacao.valueOf(rs.getString("ambito")),
                textoArray(rs, "temas"),
                TipoVotacao.valueOf(rs.getString("tipo")),
                rs.getBoolean("secreta"),
                enumOuNulo(rs.getString("voto"), TipoVoto.class),
                rs.getString("voto_origem"),
                enumOuNulo(rs.getString("origem_registro"), OrigemRegistro.class),
                rs.getString("nota"),
                null,
                booleanOuNulo(rs, "aprovada"),
                rs.getString("url_fonte")))
            .list();
    }

    public long contarDoPolitico(UUID politicoId) {
        return jdbc.sql("SELECT count(*) FROM voto_nominal WHERE politico_id = :id")
            .param("id", politicoId)
            .query(Long.class).single();
    }

    /**
     * Detalhe com placar agregado.
     *
     * Em votação simbólica não há voto individual, então o placar sai nulo em
     * vez de zero: apresentar "0 a 0" sugeriria que ninguém votou, quando o
     * que ocorre é que a Casa não registra o voto de cada um.
     */
    public Optional<VotacaoDetalhe> porId(long id) {
        return jdbc.sql("""
            SELECT v.id, v.descricao, v.casa::text AS casa, v.ambito::text AS ambito,
                   v.tipo::text AS tipo, v.secreta, v.data_votacao, v.aprovada,
                   v.proposicao_id,
                   v.url_fonte,
                   CASE v.casa WHEN 'ALESP' THEN 'ESTADUAL' ELSE 'FEDERAL' END AS esfera,
                   count(vn.id) FILTER (WHERE vn.voto = 'SIM')       AS sim,
                   count(vn.id) FILTER (WHERE vn.voto = 'NAO')       AS nao,
                   count(vn.id) FILTER (WHERE vn.voto = 'ABSTENCAO') AS abstencao,
                   count(vn.id) FILTER (
                       WHERE vn.voto IN ('AUSENTE','LICENCIADO','AUSENCIA_JUSTIFICADA',
                                         'PRESENTE_NAO_VOTOU','SECRETO',
                                         'OBSTRUCAO','ART_17')
                   ) AS outros
              FROM votacao v
              LEFT JOIN voto_nominal vn ON vn.votacao_id = v.id
             WHERE v.id = :id
             GROUP BY v.id
            """)
            .param("id", id)
            .query((rs, n) -> {
                boolean simbolica = "SIMBOLICA".equals(rs.getString("tipo"));
                boolean secreta = rs.getBoolean("secreta");
                int sim = rs.getInt("sim"), nao = rs.getInt("nao");
                int abst = rs.getInt("abstencao"), outros = rs.getInt("outros");
                boolean semVotos = sim + nao + abst + outros == 0;
                return new VotacaoDetalhe(
                    rs.getLong("id"),
                    rs.getString("descricao"),
                    CasaLegislativa.valueOf(rs.getString("casa")),
                    Esfera.valueOf(rs.getString("esfera")),
                    AmbitoVotacao.valueOf(rs.getString("ambito")),
                    TipoVotacao.valueOf(rs.getString("tipo")),
                    secreta,
                    rs.getTimestamp("data_votacao").toInstant(),
                    // Em votação secreta o placar individual sairia "0 a 0", o
                    // que sugeriria que ninguém votou — quando o que houve foi
                    // deliberação cujo conteúdo a Casa não publica. Mesmo
                    // motivo da simbólica: nulo é mais honesto que zero.
                    (simbolica || secreta || semVotos)
                        ? null : new Placar(sim, nao, abst, outros),
                    booleanOuNulo(rs, "aprovada"),
                    longOuNulo(rs, "proposicao_id"),
                    simbolica
                        ? "Votação simbólica: a Casa registra apenas o resultado, "
                          + "não o voto de cada parlamentar."
                        : secreta
                        ? "Votação secreta: a Casa registra quem participou, "
                          + "não como cada parlamentar votou."
                        : null,
                    rs.getString("url_fonte"));
            })
            .optional();
    }

    public List<Long> todosOsIds() {
        return jdbc.sql("SELECT id FROM votacao ORDER BY id").query(Long.class).list();
    }
}
