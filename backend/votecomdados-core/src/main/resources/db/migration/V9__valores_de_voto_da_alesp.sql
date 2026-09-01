-- ============================================================================
-- V9 — valores de voto que a Alesp exige, isolados numa migration própria.
--
-- Mesmo motivo da V2 e da V6: o Postgres permite ALTER TYPE ... ADD VALUE
-- dentro de uma transação, mas proíbe USAR o valor na mesma. A V10 os
-- referencia em mapeamento_voto, então os dois passos não podem morar juntos.
--
-- Verificados contra os 226.067 votos de comissão reais da Alesp (spike do
-- W12, 31/08/2026). Não são cauda inventada: a própria Alesp os publica como
-- CÓDIGO, documentado no PDF dela.
-- ============================================================================

-- Código 'S' — 2.130 votos. O parlamentar votou, formalmente, apresentando
-- parecer escrito divergente do relator. Não há tradução honesta nos valores
-- existentes: SIM/NAO inventaria direção (a amostra tem "Com o Voto em
-- Separado contrário" E "favorável"), ABSTENCAO diria o oposto do que houve
-- (ele votou), AUSENTE caluniaria por omissão.
ALTER TYPE tipo_voto_enum ADD VALUE IF NOT EXISTS 'VOTO_EM_SEPARADO' AFTER 'OBSTRUCAO';

-- Código 'B' — 186 votos. A Alesp conta "Em branco" SEPARADO de "Abstenção"
-- (código 'A', 164 votos) no placar dela. Colapsar os dois apagaria uma
-- distinção que a fonte faz, que é o erro que voto_origem existe para impedir.
ALTER TYPE tipo_voto_enum ADD VALUE IF NOT EXISTS 'BRANCO' AFTER 'ABSTENCAO';
