-- ============================================================================
-- V7 — o Senado entra no MVP, e a cobertura aprende que a esfera federal tem
--      DUAS Casas.
--
-- Origem: spike do A12 (31/08/2026) e decisão do owner de integrar o Senado.
--
-- O defeito estrutural que esta migration corrige: `cobertura_fonte` era
-- chaveada por (esfera, uf, recurso), e a linha FEDERAL/voto_nominal pertencia
-- à Câmara. A plataforma dizia a senadores que o voto nominal existia "desde
-- 2001" — quando o do Senado existe desde 1991. Errado nos dois sentidos, e
-- justamente na tabela que sustenta a promessa de neutralidade.
-- ============================================================================

-- Votação secreta É nominal: registra QUEM participou, não COMO votou. São 53%
-- das votações de plenário do Senado.
ALTER TABLE votacao ADD COLUMN secreta BOOLEAN NOT NULL DEFAULT false;

-- ----------------------------------------------------------------------------
-- cobertura_fonte ganha a Casa
-- ----------------------------------------------------------------------------

ALTER TABLE cobertura_fonte ADD COLUMN casa casa_legislativa_enum;

-- Backfill: as linhas existentes já sabem sua Casa pela fonte. TSE (trajetória
-- eleitoral) e as linhas sem fonte (fora do escopo) ficam com casa nula, que é
-- o valor correto — não são de Casa nenhuma.
UPDATE cobertura_fonte
   SET casa = fonte::text::casa_legislativa_enum
 WHERE fonte IN ('CAMARA', 'SENADO', 'ALESP');

ALTER TABLE cobertura_fonte DROP CONSTRAINT cobertura_fonte_esfera_uf_recurso_key;
ALTER TABLE cobertura_fonte
    ADD CONSTRAINT cobertura_fonte_esfera_uf_casa_recurso_key
    UNIQUE NULLS NOT DISTINCT (esfera, uf, casa, recurso);

-- ----------------------------------------------------------------------------
-- casa_do_mandato: qual Casa corresponde a cada mandato
-- ----------------------------------------------------------------------------
CREATE FUNCTION casa_do_mandato(p_cargo cargo_enum, p_uf CHAR(2))
RETURNS casa_legislativa_enum
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$
    SELECT CASE
        WHEN p_cargo = 'DEPUTADO_FEDERAL' THEN 'CAMARA'::casa_legislativa_enum
        WHEN p_cargo IN ('SENADOR', 'PRIMEIRO_SUPLENTE', 'SEGUNDO_SUPLENTE')
             THEN 'SENADO'::casa_legislativa_enum
        WHEN p_cargo IN ('DEPUTADO_ESTADUAL', 'DEPUTADO_DISTRITAL') AND p_uf = 'SP'
             THEN 'ALESP'::casa_legislativa_enum
        ELSE NULL
    END
$$;

-- ----------------------------------------------------------------------------
-- Cobertura do Senado
-- ----------------------------------------------------------------------------
INSERT INTO cobertura_fonte (esfera, uf, casa, recurso, status, fonte, disponivel_desde, observacao) VALUES
    ('FEDERAL', NULL, 'SENADO', 'proposicao',       'DISPONIVEL', 'SENADO', '1991-01-01', 'Processos legislativos do Senado. A autoria vem como texto e é ligada ao parlamentar por consulta por autor.'),
    ('FEDERAL', NULL, 'SENADO', 'votacao_plenario', 'DISPONIVEL', 'SENADO', '1991-01-01', 'Votações nominais de plenário do Senado e do Congresso Nacional.'),
    ('FEDERAL', NULL, 'SENADO', 'voto_nominal',     'DISPONIVEL', 'SENADO', '1991-01-01', 'Votos individuais desde 1991. Em 53% das votações a deliberação é secreta: a Casa registra quem participou, não como votou — o voto aparece como SECRETO, não como ausência.'),
    ('FEDERAL', NULL, 'SENADO', 'exercicio_parlamentar', 'DISPONIVEL', 'SENADO', '1991-01-01', 'A própria votação já traz a bancada inteira, com licença e ausência declaradas pela Casa. Não há derivação nossa no Senado.');

-- ----------------------------------------------------------------------------
-- Vocabulário de voto do Senado: os 13 rótulos verificados
-- ----------------------------------------------------------------------------
-- 'NA' ("Dispositivo não citado") fica DELIBERADAMENTE de fora: não é voto, e
-- não tem tradução honesta. Vai para quarentena, que é a regra do projeto.
INSERT INTO mapeamento_voto (fonte, valor_origem, voto, observacao) VALUES
    ('SENADO', 'Sim',        'SIM',       NULL),
    ('SENADO', 'Não',        'NAO',       NULL),
    ('SENADO', 'Abstenção',  'ABSTENCAO', NULL),
    ('SENADO', 'Votou',      'SECRETO',   'Votação secreta: a Casa registra que o parlamentar participou, não como votou.'),
    ('SENADO', 'P-NRV',      'PRESENTE_NAO_VOTOU', 'Presente na sessão, não registrou voto — não é ausência.'),
    ('SENADO', 'AP',         'AUSENCIA_JUSTIFICADA', 'Ausência justificada por atividade parlamentar.'),
    ('SENADO', 'MIS',        'AUSENCIA_JUSTIFICADA', 'Ausência justificada por missão da Casa, no país ou no exterior.'),
    ('SENADO', 'LS',         'LICENCIADO', 'Licença para tratamento de saúde.'),
    ('SENADO', 'LP',         'LICENCIADO', 'Licença particular.'),
    ('SENADO', 'LAP',        'LICENCIADO', 'Licença paternidade ou ao adotante.'),
    ('SENADO', 'NCom',       'AUSENTE',    'Não compareceu à sessão.'),
    ('SENADO', 'Presidente (art. 51 RISF)', 'ART_17', 'O presidente da sessão não vota, salvo nas hipóteses do regimento. Equivale ao Art. 17 da Câmara.');

-- ----------------------------------------------------------------------------
-- A projeção de leitura passa a resolver cobertura por Casa
-- ----------------------------------------------------------------------------
DROP FUNCTION reconstruir_perfil_leitura(BIGINT);
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
        foto_url                   = EXCLUDED.foto_url,
        possui_atuacao_legislativa = EXCLUDED.possui_atuacao_legislativa,
        trajetoria                 = EXCLUDED.trajetoria,
        cobertura                  = EXCLUDED.cobertura,
        reconstruido_em            = EXCLUDED.reconstruido_em,
        execucao_id                = EXCLUDED.execucao_id;

    GET DIAGNOSTICS afetados = ROW_COUNT;
    RETURN afetados;
END $$;

-- ----------------------------------------------------------------------------
-- Dados abertos: as views precisam expor casa e secreta
-- ----------------------------------------------------------------------------
-- DROP e recria em vez de CREATE OR REPLACE: as colunas novas não entram no
-- fim, e o Postgres só permite acrescentar ao fim num REPLACE.
DROP VIEW dados_abertos.cobertura_fonte;
CREATE VIEW dados_abertos.cobertura_fonte AS
    SELECT esfera, uf, casa, recurso, status, fonte, disponivel_desde, observacao
      FROM cobertura_fonte;

DROP VIEW dados_abertos.votacao;
CREATE VIEW dados_abertos.votacao AS
    SELECT id, casa, id_externo, proposicao_id, data_votacao, descricao, tipo,
           ambito, secreta, aprovada, placar_sim, placar_nao, placar_abstencao,
           url_fonte
      FROM votacao;
