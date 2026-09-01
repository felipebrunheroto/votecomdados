-- ============================================================================
-- V4 — projeção de leitura do perfil (CQRS leve).
--
-- Origem: recomendação R4 de docs/REVISAO_ARQUITETURA.md, aprovada em
-- 31/08/2026. A página de perfil exigia três consultas — a de cobertura com
-- window function sobre join — e passa a ser um SELECT por chave primária.
--
-- A justificativa que sustenta a mudança é o p95, não a carga: com ~1.000
-- visitas/dia espalhadas por milhares de páginas o cache de borda fica frio, e
-- quase toda visita paga a consulta inteira num banco burstable.
-- ============================================================================

CREATE TABLE perfil_leitura (
    politico_id                UUID PRIMARY KEY REFERENCES politico(id) ON DELETE CASCADE,
    nome_civil                 TEXT NOT NULL,
    nome_urna                  TEXT,
    foto_url                   TEXT,
    possui_atuacao_legislativa BOOLEAN NOT NULL,
    -- Chaves em camelCase de propósito: são desserializadas direto nos records
    -- do domínio, que já são o contrato de docs/API.md. Traduzir de novo em
    -- Java só criaria um lugar a mais para a projeção divergir.
    trajetoria                 JSONB NOT NULL,
    cobertura                  JSONB NOT NULL,
    reconstruido_em            TIMESTAMPTZ NOT NULL DEFAULT now(),
    execucao_id                BIGINT REFERENCES ingestao_execucao(id)
);

-- Reconstrói a projeção inteira. Roda ao fim de cada ingestão bem-sucedida.
-- Reconstrução total, não incremental: a coorte tem ~28 mil pessoas e a
-- operação leva segundos — não vale a complexidade (e o risco de divergência)
-- de rastrear o que mudou.
CREATE FUNCTION reconstruir_perfil_leitura(p_execucao_id BIGINT DEFAULT NULL)
RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE
    afetados BIGINT;
BEGIN
    INSERT INTO perfil_leitura AS pl (
        politico_id, nome_civil, nome_urna, foto_url,
        possui_atuacao_legislativa, trajetoria, cobertura,
        reconstruido_em, execucao_id
    )
    SELECT p.id, p.nome_civil, p.nome_urna, p.foto_url, p.possui_atuacao_legislativa,
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
      -- Cobertura pertinente, com precedência por UF: uma linha com UF
      -- específica ganha da genérica. Sem isso, ou São Paulo herdaria "fora do
      -- escopo", ou os outros 26 estados apareceriam como cobertos.
      LEFT JOIN LATERAL (
          SELECT jsonb_agg(jsonb_build_object(
                     'esfera',          x.esfera::text,
                     'uf',              x.uf,
                     'recurso',         x.recurso,
                     'status',          x.status::text,
                     'disponivelDesde', x.disponivel_desde::text,
                     'observacao',      x.observacao)
                 ORDER BY x.esfera, x.uf NULLS FIRST, x.recurso) AS dados
            FROM (
                SELECT DISTINCT cf.esfera, cf.uf, cf.recurso, cf.status,
                       cf.disponivel_desde, cf.observacao
                  FROM (
                      SELECT cf.*,
                             row_number() OVER (
                                 PARTITION BY cf.esfera, cf.recurso, u.uf
                                 ORDER BY cf.uf NULLS LAST
                             ) AS precedencia
                        FROM cobertura_fonte cf
                        JOIN (SELECT DISTINCT esfera, uf FROM candidatura
                               WHERE politico_id = p.id) u ON u.esfera = cf.esfera
                       WHERE cf.uf IS NULL OR cf.uf = u.uf
                  ) cf
                 WHERE cf.precedencia = 1
                   AND cf.recurso <> 'candidatura'
            ) x
      ) c ON true
    ON CONFLICT (politico_id) DO UPDATE SET
        nome_civil                 = EXCLUDED.nome_civil,
        nome_urna                  = EXCLUDED.nome_urna,
        foto_url                   = EXCLUDED.foto_url,
        possui_atuacao_legislativa = EXCLUDED.possui_atuacao_legislativa,
        trajetoria                 = EXCLUDED.trajetoria,
        cobertura                  = EXCLUDED.cobertura,
        reconstruido_em            = EXCLUDED.reconstruido_em,
        execucao_id                = EXCLUDED.execucao_id;

    GET DIAGNOSTICS afetados = ROW_COUNT;
    RETURN afetados;
END $$;
