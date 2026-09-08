-- `ano = 0` era sentinela, e sentinela vaza.
--
-- O portal da Câmara publica `ano: "0"` para as peças acessórias de
-- tramitação — parecer do relator (PRL), parecer (PAR), emenda de plenário
-- (EMP), substitutivo (SBT), emenda de relator (EMR) e afins. Não é dado
-- faltando: essas peças são numeradas DENTRO da tramitação de outra matéria e
-- não carregam ano na designação oficial. "EMR 1" existe; "EMR 1/2024" não.
--
-- São 41.937 registros, mais que qualquer ano real da Câmara na base
-- (08/09/2026). O zero já causou dois defeitos:
--
--   1. O título da página saía como "EMR 1/0".
--   2. O corte de pré-render (`ano >= 2026`) as excluía em silêncio, inclusive
--      as 16.902 apresentadas em 2026 — peças do mandato corrente de quem é
--      candidato, que é exatamente o que o site existe para mostrar.
--
-- NULL é a representação honesta de "não tem", e é a convenção que o próprio
-- schema já usa em `numero`. Quem consome o pacote de dados abertos passa a
-- ver ausência explícita em vez de um zero que parece ano.

ALTER TABLE proposicao ALTER COLUMN ano DROP NOT NULL;

UPDATE proposicao SET ano = NULL WHERE ano = 0;
