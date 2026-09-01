-- ============================================================================
-- V12 — quem tem atuação legislativa passa a ser CALCULADO, não presumido.
--
-- `politico.possui_atuacao_legislativa` nasceu NOT NULL DEFAULT false na V1, e
-- nenhum job jamais a atualizou (achado A1, 01/09/2026). É ela que decide
-- quais perfis o build estático gera (`generateStaticParams`) e o filtro
-- `comAtuacao` da busca — com a coluna sempre false, nenhum perfil seria
-- pré-renderizado e todo candidato apareceria como "sem mandato anterior".
-- ============================================================================

CREATE FUNCTION marcar_atuacao_legislativa() RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE
    afetados BIGINT;
BEGIN
    WITH atuantes AS (
        SELECT DISTINCT politico_id FROM proposicao_autor WHERE politico_id IS NOT NULL
        UNION
        SELECT DISTINCT politico_id FROM voto_nominal WHERE origem_registro = 'FONTE'
        UNION
        SELECT DISTINCT politico_id FROM mandato_exercicio
    ),
    calculado AS (
        SELECT p.id, (a.politico_id IS NOT NULL) AS atuante
          FROM politico p
          LEFT JOIN atuantes a ON a.politico_id = p.id
    ),
    atualizados AS (
        UPDATE politico p
           SET possui_atuacao_legislativa = c.atuante
          FROM calculado c
         WHERE c.id = p.id
           AND p.possui_atuacao_legislativa <> c.atuante
        RETURNING 1
    )
    SELECT count(*) INTO afetados FROM atualizados;

    RETURN afetados;
END $$;

-- Aplica imediatamente, para a coorte já carregada não ficar "zerada" até a
-- próxima ingestão rodar.
SELECT marcar_atuacao_legislativa();
