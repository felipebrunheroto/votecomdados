-- ============================================================================
-- V8 — `NAO_INFORMADO` em status_candidatura_enum.
--
-- Achado ao verificar o arquivo real do TSE (31/08/2026), depois que o owner
-- conseguiu baixá-lo: `DS_SITUACAO_CANDIDATURA` vem com o sentinela `#NE`
-- (não especificado) em **100% das 20.809 candidaturas de 2026** — o registro
-- ainda está sendo julgado pela Justiça Eleitoral.
--
-- Sem este valor, a única tradução possível era `APTO`, e a plataforma diria a
-- cada eleitor que todo candidato está apto — uma afirmação em nome do TSE que
-- o TSE não fez. É o mesmo erro do B5, em outro campo.
--
-- Valor único numa migration própria, pelo motivo da V2 e da V6: o Postgres
-- proíbe usar um valor de enum na mesma transação em que ele é criado.
-- ============================================================================

ALTER TYPE status_candidatura_enum ADD VALUE IF NOT EXISTS 'NAO_INFORMADO'
    BEFORE 'DEFERIDO';
