-- Remove as proposituras da Alesp que estão fora do recorte do projeto.
--
-- A carga da Câmara sempre exigiu autor na coorte; a da Alesp gravava a série
-- inteira, sem filtro. Em 08/09/2026 o primeiro ciclo incremental completo
-- levou a base de 46.433 para 346.481 proposições -- centenas de milhares de
-- páginas de quem não se apresenta ao eleitorado em 2026, que é o oposto do
-- recorte do projeto, e o que fez o build do site estourar a pilha do Next.
--
-- A ingestão passou a filtrar na origem (JobDaAlesp.documentosComAutorNaCoorte),
-- mas isso só vale para cargas novas: o que já entrou continua aqui.
--
-- NÃO apaga propositura que tenha votação: `votacao.proposicao_id` referencia
-- `proposicao` SEM cascata, e a votação é registro de deliberação que existe
-- por si. Preferir deixar um punhado de proposituras a mais do que apagar
-- votação -- ou falhar a migração por violação de FK.
--
-- `proposicao_tema`, `proposicao_autor` e `proposicao_historico` têm ON DELETE
-- CASCADE e somem junto.
--
-- Idempotente: reexecutar não encontra mais nada a apagar.

DELETE FROM proposicao p
 WHERE p.casa = 'ALESP'
   AND NOT EXISTS (
       SELECT 1 FROM proposicao_autor pa
        WHERE pa.proposicao_id = p.id
          AND pa.politico_id IS NOT NULL)
   AND NOT EXISTS (
       SELECT 1 FROM votacao v
        WHERE v.proposicao_id = p.id);
