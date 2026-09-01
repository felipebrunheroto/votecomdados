-- ============================================================================
-- V2 — novos valores de enum, isolados numa migration própria.
--
-- Por que sozinhos: o Postgres permite ALTER TYPE ... ADD VALUE dentro de uma
-- transação (e o Flyway roda cada migration em uma), mas PROÍBE usar o valor
-- recém-criado na mesma transação. Como a V3 referencia 'LICENCIADO' num
-- CHECK, os dois passos não podem morar juntos.
--
-- A posição de cada valor é declarada com AFTER para que o enum tenha a mesma
-- ordem de db/schema.sql — assim o banco criado do zero e o migrado por Flyway
-- são idênticos, e não apenas equivalentes.
-- ============================================================================

-- Ausência e licença NÃO são publicadas por nenhuma fonte: são derivadas do
-- cruzamento entre a votação e quem estava em exercício na data.
ALTER TYPE tipo_voto_enum ADD VALUE IF NOT EXISTS 'LICENCIADO' AFTER 'AUSENTE';

-- Parlamentar que não é candidato em 2026 é caso ESPERADO, não defeito. Sem um
-- motivo próprio, ele cairia em POLITICO_NAO_RESOLVIDO e a métrica de
-- quarentena — cujo valor esperado é zero — nasceria com dezenas de milhares
-- de linhas.
ALTER TYPE motivo_rejeicao_enum ADD VALUE IF NOT EXISTS 'FORA_DA_COORTE'
    AFTER 'POLITICO_NAO_RESOLVIDO';

ALTER TYPE motivo_rejeicao_enum ADD VALUE IF NOT EXISTS 'SITUACAO_NAO_MAPEADA'
    AFTER 'VALOR_VOTO_NAO_MAPEADO';
