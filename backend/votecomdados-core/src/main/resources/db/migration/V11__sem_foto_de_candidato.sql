-- ============================================================================
-- V11 — a plataforma não terá fotos, e a coluna sai junto com a decisão.
--
-- Decisão de produto (01/09/2026): não haverá foto de candidato no sistema.
--
-- A coluna poderia simplesmente ficar lá, nula para sempre — mas seria uma
-- promessa que não vamos cumprir, exposta no contrato público da API. Campo
-- que nunca será preenchido é dívida que se cobra em confiança: quem integra
-- assume que um dia virá dado.
--
-- Remover é seguro: `foto_url` nunca foi escrita por nenhum job — não há dado
-- a perder, só superfície.
--
-- `dados_abertos.politico` NÃO precisa mudar: a view nunca expôs a coluna.
-- ============================================================================

-- A projeção copia as colunas de `politico`, então ela é reescrita ANTES do
-- DROP. O corpo é o mesmo da V7, sem as três referências a foto_url.
CREATE OR REPLACE FUNCTION reconstruir_perfil_leitura(p_execucao_id BIGINT DEFAULT NULL)
RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE
    afetados BIGINT;
BEGIN
    INSERT INTO perfil_leitura AS pl (
        politico_id, nome_civil, nome_urna,
        possui_atuacao_legislativa, trajetoria, cobertura,
        reconstruido_em, execucao_id
    )
    SELECT p.id, p.nome_civil, p.nome_urna, p.possui_atuacao_legislativa,
           coalesce(t.dados, '[]'::jsonb),
           coalesce(c.dados, '[]'::jsonb),
           now(), p_execucao_id
      FROM politico p
      -- Trajetória: da disputa mais recente para a mais antiga.
      LEFT JOIN LATERAL (
          SELECT jsonb_agg(jsonb_build_object(
                     'anoEleicao',   cand.ano_eleicao,
                     'cargo',        cand.cargo::text,
                     'esfera',       cand.esfera::text,
                     'uf',           cand.uf,
                     'municipio',    cand.municipio,
                     'partidoSigla', cand.partido_sigla,
                     'status',       cand.status::text,
                     'eleito',       cand.eleito)
                 ORDER BY cand.ano_eleicao DESC, cand.turno DESC) AS dados
            FROM candidatura cand
           WHERE cand.politico_id = p.id
      ) t ON true
      -- Cobertura pertinente à pessoa, resolvida em dois eixos:
      --
      --   UF   -> PRECEDÊNCIA: linha específica ganha da genérica, porque a
      --           pessoa tem uma UF por esfera e queremos UMA resposta.
      --   CASA -> PARTIÇÃO: quem foi deputado E senador tem DUAS coberturas
      --           federais legítimas, com datas de início diferentes (Câmara
      --           2001, Senado 1991). Colapsá-las mentiria sobre uma das duas.
      --
      -- O particionamento é pelo contexto da PESSOA (ctx), não pela linha:
      -- assim SP continua ganhando do fallback estadual, e as duas Casas
      -- federais continuam aparecendo lado a lado.
      LEFT JOIN LATERAL (
          SELECT jsonb_agg(jsonb_build_object(
                     'esfera',          x.esfera::text,
                     'uf',              x.uf,
                     'casa',            x.casa::text,
                     'recurso',         x.recurso,
                     'status',          x.status::text,
                     'disponivelDesde', x.disponivel_desde::text,
                     'observacao',      x.observacao)
                 ORDER BY x.esfera, x.casa NULLS FIRST, x.uf NULLS FIRST,
                          x.recurso) AS dados
            FROM (
                SELECT DISTINCT r.esfera, r.uf, r.casa, r.recurso, r.status,
                       r.disponivel_desde, r.observacao
                  FROM (
                      SELECT cf.*,
                             row_number() OVER (
                                 PARTITION BY cf.esfera, cf.recurso,
                                              ctx.casa, ctx.uf
                                 ORDER BY cf.uf NULLS LAST, cf.casa NULLS LAST
                             ) AS precedencia
                        FROM cobertura_fonte cf
                        JOIN (SELECT DISTINCT c.esfera, c.uf,
                                     casa_do_mandato(c.cargo, c.uf) AS casa
                                FROM candidatura c
                               WHERE c.politico_id = p.id) ctx
                          ON ctx.esfera = cf.esfera
                       WHERE (cf.uf IS NULL OR cf.uf = ctx.uf)
                         AND (cf.casa IS NULL OR cf.casa = ctx.casa)
                  ) r
                 WHERE r.precedencia = 1
                   AND r.recurso <> 'candidatura'
            ) x
      ) c ON true
    ON CONFLICT (politico_id) DO UPDATE SET
        nome_civil                 = EXCLUDED.nome_civil,
        nome_urna                  = EXCLUDED.nome_urna,
        possui_atuacao_legislativa = EXCLUDED.possui_atuacao_legislativa,
        trajetoria                 = EXCLUDED.trajetoria,
        cobertura                  = EXCLUDED.cobertura,
        reconstruido_em            = EXCLUDED.reconstruido_em,
        execucao_id                = EXCLUDED.execucao_id;

    GET DIAGNOSTICS afetados = ROW_COUNT;
    RETURN afetados;
END $$;


-- Agora sim, as colunas. A ordem importa: a projeção já não as referencia.
ALTER TABLE perfil_leitura DROP COLUMN foto_url;
ALTER TABLE politico DROP COLUMN foto_url;
