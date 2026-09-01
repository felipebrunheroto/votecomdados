-- ============================================================================
-- V6 — valores de voto que o Senado exige, isolados numa migration própria.
--
-- Mesmo motivo da V2: o Postgres permite ALTER TYPE ... ADD VALUE dentro de
-- uma transação, mas proíbe USAR o valor na mesma. A V7 os referencia em
-- mapeamento_voto, então os dois passos não podem morar juntos.
--
-- Verificados contra 22 mil linhas reais do Senado (spike do A12, 31/08/2026).
-- Não são cauda: juntos somam 12,3 mil dessas linhas.
-- ============================================================================

-- Participou de votação secreta: a Casa registra QUEM votou, não COMO. É o
-- rótulo mais frequente do Senado (7.569 na amostra). Forçá-lo a SIM/NAO seria
-- inventar posição; a AUSENTE, caluniar por omissão.
ALTER TYPE tipo_voto_enum ADD VALUE IF NOT EXISTS 'SECRETO' AFTER 'LICENCIADO';

-- Estava na sessão e não registrou voto. Não é falta.
ALTER TYPE tipo_voto_enum ADD VALUE IF NOT EXISTS 'PRESENTE_NAO_VOTOU' AFTER 'LICENCIADO';

-- Ausência por trabalho da Casa (missão oficial, atividade parlamentar).
-- Diferente de licença e diferente de falta.
ALTER TYPE tipo_voto_enum ADD VALUE IF NOT EXISTS 'AUSENCIA_JUSTIFICADA' AFTER 'LICENCIADO';
