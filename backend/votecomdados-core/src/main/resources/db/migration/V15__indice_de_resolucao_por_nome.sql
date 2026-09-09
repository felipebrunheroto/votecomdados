-- Índice para o casamento por nome civil + data de nascimento.
--
-- O índice que já existia é `gin (unaccent_imutavel(nome_civil) gin_trgm_ops)`
-- — SEM o `upper`, e de trigrama, para busca por similaridade. As duas
-- consultas de resolução de identidade comparam
-- `unaccent_imutavel(upper(nome_civil))` por igualdade. Expressão diferente:
-- o índice não é usado, e cada consulta vira varredura sequencial.
--
-- Com uma eleição só (21 mil pessoas) isso passava despercebido. Na carga
-- multi-ano não passa: um pacote municipal traz ~450 mil candidaturas, quase
-- todas de gente fora da coorte de 2026 — nenhuma casa por candidatura nem
-- por CPF, então TODAS chegam ao último recurso. Cada uma varre uma tabela
-- que cresce a cada inserção; o custo é quadrático.
--
-- Na prática (08/09/2026): 26.263 candidaturas de 2014 carregaram em 96
-- segundos; o pacote municipal de 2016 passou de duas horas sem concluir.
--
-- A coluna de nascimento entra no índice porque as duas consultas filtram por
-- ela junto do nome — é o par que desempata homônimo, e desempatar sem tocar
-- na tabela é o ponto.
--
-- CONCURRENTLY não é usado de propósito: o Flyway roda migração em transação,
-- e `CREATE INDEX CONCURRENTLY` não pode. A tabela é pequena quando esta
-- migração roda; o lock é de segundos.

CREATE INDEX IF NOT EXISTS idx_politico_nome_civil_nascimento
    ON politico (unaccent_imutavel(upper(nome_civil)), data_nascimento);
