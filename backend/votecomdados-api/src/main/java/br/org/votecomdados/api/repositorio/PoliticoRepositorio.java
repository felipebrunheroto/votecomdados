package br.org.votecomdados.api.repositorio;

import br.org.votecomdados.core.dominio.Enums.Cargo;
import br.org.votecomdados.core.dominio.Modelo.*;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import static br.org.votecomdados.api.repositorio.MapeadoresSql.*;

@Repository
public class PoliticoRepositorio {

    private static final Logger log = LoggerFactory.getLogger(PoliticoRepositorio.class);

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    PoliticoRepositorio(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Busca com filtros. A candidatura de 2026 é o que coloca a pessoa na base,
     * então o JOIN com ela não é opcional — é a definição da coorte.
     *
     * `unaccent_imutavel` é usada em vez de `unaccent` porque é a função sobre
     * a qual os índices GIN foram criados; chamar a outra faria o Postgres
     * ignorar o índice e varrer a tabela.
     */
    private static final String SELECT_BUSCA = """
        SELECT p.id, p.nome_civil, p.nome_urna,
               p.possui_atuacao_legislativa,
               c.cargo, c.uf, c.partido_sigla, c.status
          FROM politico p
          JOIN candidatura c ON c.politico_id = p.id AND c.ano_eleicao = 2026
         WHERE (:temTexto = FALSE OR p.nome_busca @@ plainto_tsquery('portuguese', :q)
                                 OR unaccent_imutavel(p.nome_civil) ILIKE :like
                                 OR unaccent_imutavel(coalesce(p.nome_urna, '')) ILIKE :like)
           AND (:cargo::text IS NULL OR c.cargo::text = :cargo)
           AND (:uf::text    IS NULL OR c.uf = :uf)
           AND (:comAtuacao = FALSE OR p.possui_atuacao_legislativa)
        """;

    public List<PoliticoResumo> buscar(String q, Cargo cargo, String uf,
                                       boolean comAtuacao, int limite, int offset) {
        return comFiltros(SELECT_BUSCA + " ORDER BY p.nome_civil LIMIT :limite OFFSET :offset",
                          q, cargo, uf, comAtuacao)
            .param("limite", limite)
            .param("offset", offset)
            .query((rs, n) -> new PoliticoResumo(
                UUID.fromString(rs.getString("id")),
                rs.getString("nome_civil"),
                rs.getString("nome_urna"),
                Cargo.valueOf(rs.getString("cargo")),
                rs.getString("uf"),
                rs.getString("partido_sigla"),
                enumOuNulo(rs.getString("status"),
                    br.org.votecomdados.core.dominio.Enums.StatusCandidatura.class),
                rs.getBoolean("possui_atuacao_legislativa")))
            .list();
    }

    public long contar(String q, Cargo cargo, String uf, boolean comAtuacao) {
        return comFiltros("SELECT count(*) FROM (" + SELECT_BUSCA + ") AS filtrados",
                          q, cargo, uf, comAtuacao)
            .query(Long.class)
            .single();
    }

    /** O SQL entra pronto: StatementSpec não permite trocá-lo depois de criado. */
    private JdbcClient.StatementSpec comFiltros(String sql, String q, Cargo cargo,
                                                String uf, boolean comAtuacao) {
        boolean temTexto = q != null && !q.isBlank();
        return jdbc.sql(sql)
            .param("q", temTexto ? q : "")
            .param("temTexto", temTexto)
            .param("like", temTexto ? "%" + q.trim() + "%" : "%")
            .param("cargo", cargo == null ? null : cargo.name())
            .param("uf", uf == null || uf.isBlank() ? null : uf)
            .param("comAtuacao", comAtuacao);
    }

    /**
     * Perfil pela projeção de leitura: um SELECT por chave primária em vez das
     * três consultas normalizadas (a de cobertura com window function sobre
     * join). Ver R4 em docs/REVISAO_ARQUITETURA.md.
     *
     * O que sustenta a projeção é o p95, não a carga: com o tráfego espalhado
     * por milhares de páginas o cache de borda fica frio, e quase toda visita
     * paga a consulta inteira num banco burstable.
     */
    public Optional<PoliticoPerfil> perfil(UUID id) {
        var daProjecao = jdbc.sql("""
            SELECT nome_civil, nome_urna, possui_atuacao_legislativa,
                   trajetoria::text AS trajetoria, cobertura::text AS cobertura
              FROM perfil_leitura WHERE politico_id = :id
            """)
            .param("id", id)
            .query((rs, n) -> new PoliticoPerfil(
                id, rs.getString("nome_civil"), rs.getString("nome_urna"),
                rs.getBoolean("possui_atuacao_legislativa"),
                lista(rs.getString("trajetoria"), Candidatura[].class),
                lista(rs.getString("cobertura"), Cobertura[].class)))
            .optional();

        if (daProjecao.isPresent()) return daProjecao;

        // Projeção ausente para alguém que existe é BUG DE PIPELINE, não caso
        // normal: a reconstrução ao fim da ingestão deve cobrir toda a coorte.
        // Cair no caminho normalizado devolve dado correto (mais devagar) em
        // vez de um 404 mentiroso — mas o aviso precisa existir, senão o
        // fallback esconde a falha, que é exatamente o que este projeto trata
        // como pior modo de erro.
        return perfilNormalizado(id);
    }

    private Optional<PoliticoPerfil> perfilNormalizado(UUID id) {
        var base = jdbc.sql("""
            SELECT id, nome_civil, nome_urna, possui_atuacao_legislativa
              FROM politico WHERE id = :id
            """)
            .param("id", id)
            .query((rs, n) -> new Object[]{
                rs.getString("nome_civil"), rs.getString("nome_urna"),
                rs.getBoolean("possui_atuacao_legislativa")})
            .optional();

        if (base.isEmpty()) return Optional.empty();   // não existe mesmo: 404 correto
        Object[] p = base.get();

        log.warn("perfil_leitura sem linha para politico {} — projecao desatualizada; "
                 + "servindo pelo caminho normalizado", id);

        return Optional.of(new PoliticoPerfil(
            id, (String) p[0], (String) p[1], (Boolean) p[2],
            trajetoria(id), coberturaRelevante(id)));
    }

    /**
     * Desserializa usando o ObjectMapper da própria aplicação — o mesmo que
     * serializa a resposta. Assim a projeção não pode divergir do contrato por
     * diferença de convenção (nomes, datas, nulos).
     */
    private <T> List<T> lista(String jsonArray, Class<T[]> tipoArray) {
        try {
            return List.of(json.readValue(jsonArray, tipoArray));
        } catch (Exception e) {
            throw new IllegalStateException(
                "projecao perfil_leitura ilegivel para " + tipoArray.getSimpleName(), e);
        }
    }

    /** Da disputa mais recente para a mais antiga. */
    public List<Candidatura> trajetoria(UUID id) {
        return jdbc.sql("""
            SELECT ano_eleicao, cargo::text AS cargo, esfera::text AS esfera, uf, municipio,
                   partido_sigla, status::text AS status, eleito
              FROM candidatura
             WHERE politico_id = :id
             ORDER BY ano_eleicao DESC, turno DESC
            """)
            .param("id", id)
            .query((rs, n) -> candidatura(rs))
            .list();
    }

    /**
     * Cobertura pertinente a este candidato, resolvida em dois eixos.
     *
     * <p><b>UF é precedência:</b> uma linha com UF específica ganha da genérica.
     * Sem isso, ou São Paulo herdaria "fora do escopo", ou os outros 26 estados
     * apareceriam como cobertos.
     *
     * <p><b>Casa é partição:</b> quem foi deputado <i>e</i> senador tem duas
     * coberturas federais legítimas, com datas de início diferentes (Câmara
     * 2001, Senado 1991). Colapsá-las mentiria sobre uma das duas — era o que
     * acontecia antes de `cobertura_fonte` ter a coluna `casa`.
     *
     * <p>O particionamento é pelo contexto da pessoa, não pela linha: assim SP
     * continua ganhando do fallback estadual e as duas Casas federais aparecem
     * lado a lado. Precisa continuar idêntico ao de
     * {@code reconstruir_perfil_leitura()} — o teste de integração compara os
     * dois caminhos.
     */
    public List<Cobertura> coberturaRelevante(UUID id) {
        return jdbc.sql("""
            WITH contexto AS (
                SELECT DISTINCT c.esfera, c.uf,
                       casa_do_mandato(c.cargo, c.uf) AS casa
                  FROM candidatura c WHERE c.politico_id = :id
            ),
            candidatas AS (
                SELECT cf.*,
                       row_number() OVER (
                           PARTITION BY cf.esfera, cf.recurso, ctx.casa, ctx.uf
                           ORDER BY cf.uf NULLS LAST, cf.casa NULLS LAST
                       ) AS precedencia
                  FROM cobertura_fonte cf
                  JOIN contexto ctx ON ctx.esfera = cf.esfera
                 WHERE (cf.uf IS NULL OR cf.uf = ctx.uf)
                   AND (cf.casa IS NULL OR cf.casa = ctx.casa)
            )
            SELECT x.esfera::text AS esfera, x.uf, x.casa::text AS casa, x.recurso,
                   x.status::text AS status, x.disponivel_desde, x.observacao
              FROM (
                  SELECT DISTINCT esfera, uf, casa, recurso, status,
                         disponivel_desde, observacao
                    FROM candidatas
                   WHERE precedencia = 1
                     AND recurso <> 'candidatura'
              ) x
             -- Ordena pelo ENUM (FEDERAL, ESTADUAL, MUNICIPAL), não pelo texto
             -- já convertido: `ORDER BY esfera` sobre a coluna de saída sairia
             -- em ordem alfabética, e o eleitor leria a atuação estadual antes
             -- da federal. É a ordem de docs/API.md, e a mesma que
             -- reconstruir_perfil_leitura() usa — as duas não podem divergir.
             ORDER BY x.esfera, x.casa NULLS FIRST, x.uf NULLS FIRST, x.recurso
            """)
            .param("id", id)
            .query((rs, n) -> cobertura(rs))
            .list();
    }

    /** Ids pré-renderizáveis: só quem tem atuação a exibir (ver FRONTEND.md § 1). */
    public List<UUID> idsComAtuacao() {
        return jdbc.sql("SELECT id FROM politico WHERE possui_atuacao_legislativa ORDER BY id")
            .query((rs, n) -> UUID.fromString(rs.getString("id")))
            .list();
    }
}
