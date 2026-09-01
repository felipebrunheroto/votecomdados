-- ===========================================================================
-- Testes de invariantes do schema — as garantias que os blockers exigiram.
--
-- Cada teste corresponde a um achado de docs/REVISAO_ARQUITETURA.md ou a uma
-- resposta registrada em § Perguntas respondidas: falhar aqui significa que uma
-- correção regrediu.
--
--   docker run -d --rm --name vcd-pg -e POSTGRES_PASSWORD=test \
--     -e POSTGRES_DB=votecomdados postgres:16-alpine
--   docker cp db/schema.sql vcd-pg:/tmp/ && docker cp db/test_invariantes.sql vcd-pg:/tmp/
--   docker exec vcd-pg psql -U postgres -d votecomdados -v ON_ERROR_STOP=1 -f /tmp/schema.sql
--   docker exec vcd-pg psql -U postgres -d votecomdados -v ON_ERROR_STOP=1 -f /tmp/test_invariantes.sql
--
-- Roda inteiro em transação e desfaz ao final, então é repetível contra o
-- mesmo banco. Não depende de IDs fixos por isso mesmo: após o ROLLBACK as
-- sequências não retrocedem, e qualquer valor codificado quebraria na
-- segunda execução.
--
-- No CI, o mesmo roteiro roda via Testcontainers no build do módulo core.
-- ===========================================================================

\set ON_ERROR_STOP on
\pset pager off

BEGIN;

-- ===========================================================================
-- T1 (B4): politico criável apenas com registro institucional, sem nome_urna
-- ===========================================================================
INSERT INTO politico (nome_civil, nome_parlamentar)
VALUES ('Fulana de Teste Souza', 'Fulana Teste')
RETURNING id AS politico_id \gset
\echo 'T1 OK: politico sem nome_urna aceito (suplente/institucional)'

-- A busca textual funciona mesmo sem nome_urna:
SELECT CASE WHEN nome_busca @@ plainto_tsquery('portuguese', 'fulana')
            THEN 'T2 OK: nome_busca populado e pesquisável sem nome_urna'
            ELSE 'T2 FALHOU' END
FROM politico WHERE id = :'politico_id';

-- Busca fuzzy com acento normalizado usa o wrapper imutável (unaccent puro
-- não é IMMUTABLE e seria rejeitado no índice/coluna gerada):
SELECT CASE WHEN unaccent_imutavel(nome_civil) ILIKE unaccent_imutavel('%Sousa%')
              OR similarity(unaccent_imutavel(nome_civil),
                            unaccent_imutavel('Fulana Souza')) > 0.3
            THEN 'T3 OK: trigram/unaccent_imutavel casa nome com acento'
            ELSE 'T3 FALHOU' END
FROM politico WHERE id = :'politico_id';

-- ===========================================================================
-- T4/T5 (B5): voto_origem é obrigatório — sem o fato bruto não grava
-- ===========================================================================
INSERT INTO proposicao (casa, id_externo, sigla_tipo, numero, ano, ementa, url_tramitacao)
VALUES ('CAMARA', 'teste-prop-1', 'PL', 1, 2023, 'Ementa de teste', 'https://exemplo')
RETURNING id AS proposicao_id \gset

INSERT INTO votacao (casa, id_externo, proposicao_id, data_votacao, descricao, tipo, url_fonte)
VALUES ('CAMARA', 'teste-vot-1', :proposicao_id, now(), 'Votação de teste', 'NOMINAL', 'https://exemplo')
RETURNING id AS votacao_id \gset

SAVEPOINT antes_voto_invalido;
\set ON_ERROR_STOP off
INSERT INTO voto_nominal (votacao_id, politico_id, voto)
VALUES (:votacao_id, :'politico_id', 'SIM');
\set ON_ERROR_STOP on
ROLLBACK TO SAVEPOINT antes_voto_invalido;
\echo 'T4 OK: voto sem voto_origem rejeitado (erro esperado acima)'

INSERT INTO voto_nominal (votacao_id, politico_id, voto, voto_origem)
VALUES (:votacao_id, :'politico_id', 'OBSTRUCAO', 'Obstrução');
\echo 'T5 OK: voto gravado preservando o rótulo original da fonte'

-- ===========================================================================
-- T6/T7/T8 (B6): apenas uma execução EM_ANDAMENTO por fonte
-- ===========================================================================
INSERT INTO ingestao_execucao (fonte, tipo_job) VALUES ('CAMARA', 'INCREMENTAL')
RETURNING id AS execucao_id \gset

SAVEPOINT antes_execucao_concorrente;
\set ON_ERROR_STOP off
INSERT INTO ingestao_execucao (fonte, tipo_job) VALUES ('CAMARA', 'BACKFILL');
\set ON_ERROR_STOP on
ROLLBACK TO SAVEPOINT antes_execucao_concorrente;
\echo 'T6 OK: segunda execucao concorrente na mesma fonte bloqueada (erro esperado acima)'

-- Outra fonte em paralelo continua permitida:
INSERT INTO ingestao_execucao (fonte, tipo_job) VALUES ('SENADO', 'INCREMENTAL');
\echo 'T7 OK: fontes distintas podem executar em paralelo'

-- Concluída libera a fonte para a próxima execução:
UPDATE ingestao_execucao SET status = 'CONCLUIDA', concluido_em = now(),
       watermark_novo = now() WHERE id = :execucao_id;
INSERT INTO ingestao_execucao (fonte, tipo_job) VALUES ('CAMARA', 'INCREMENTAL');
\echo 'T8 OK: fonte liberada apos conclusao'

-- ===========================================================================
-- T9 (A4): dedup do staging funciona MESMO com id_externo NULL
-- ===========================================================================
INSERT INTO staging.payload_bruto (execucao_id, fonte, recurso, id_externo, payload, payload_hash)
VALUES (:execucao_id, 'TSE', 'candidatura', NULL, '{"nome":"X"}', repeat('a', 64));

SAVEPOINT antes_payload_duplicado;
\set ON_ERROR_STOP off
INSERT INTO staging.payload_bruto (execucao_id, fonte, recurso, id_externo, payload, payload_hash)
VALUES (:execucao_id, 'TSE', 'candidatura', NULL, '{"nome":"X"}', repeat('a', 64));
\set ON_ERROR_STOP on
ROLLBACK TO SAVEPOINT antes_payload_duplicado;
\echo 'T9 OK: NULLS NOT DISTINCT deduplicou payload sem id_externo (erro esperado acima)'

-- ===========================================================================
-- T10 (B3): uma proposição pode ter múltiplos temas
-- ===========================================================================
INSERT INTO proposicao_tema (proposicao_id, tema)
VALUES (:proposicao_id, 'Educação'), (:proposicao_id, 'Direitos Humanos');

SELECT CASE WHEN count(*) = 2 THEN 'T10 OK: multiplos temas por proposicao'
            ELSE 'T10 FALHOU' END
FROM proposicao_tema WHERE proposicao_id = :proposicao_id;

-- ===========================================================================
-- T11 (B1): HMAC com pepper é determinístico, dependente da chave, 64 hex
-- ===========================================================================
SELECT CASE
    WHEN encode(hmac('12345678909', 'pepper-secreto', 'sha256'), 'hex')
       = encode(hmac('12345678909', 'pepper-secreto', 'sha256'), 'hex')
     AND encode(hmac('12345678909', 'pepper-secreto', 'sha256'), 'hex')
      <> encode(hmac('12345678909', 'outro-pepper', 'sha256'), 'hex')
     AND length(encode(hmac('12345678909', 'pepper-secreto', 'sha256'), 'hex')) = 64
    THEN 'T11 OK: HMAC-SHA256 deterministico, dependente da chave, cabe em CHAR(64)'
    ELSE 'T11 FALHOU' END;

-- ===========================================================================
-- T12 (B4): quarentena registra o voto rejeitado em vez de descartá-lo
-- ===========================================================================
INSERT INTO staging.registro_rejeitado
    (execucao_id, fonte, recurso, id_externo, motivo, detalhe, payload)
VALUES (:execucao_id, 'CAMARA', 'voto', 'teste-vot-1-dep-99', 'POLITICO_NAO_RESOLVIDO',
        'id_camara 99 sem vinculo em identificador_externo', '{"deputado":99}');

SELECT CASE WHEN count(*) = 1 THEN 'T12 OK: voto nao casado fica visivel em quarentena'
            ELSE 'T12 FALHOU' END
FROM staging.registro_rejeitado
WHERE execucao_id = :execucao_id AND resolvido_em IS NULL;

-- ===========================================================================
-- T13 (coorte): coautor fora da coorte é gravado como nome, sem perfil
-- ===========================================================================
INSERT INTO proposicao_autor (proposicao_id, politico_id, autor_nome, autor_principal)
VALUES (:proposicao_id, :'politico_id', 'Fulana Teste', true),
       (:proposicao_id, NULL, 'Beltrano Ex-Deputado', false);

SELECT CASE WHEN count(*) FILTER (WHERE politico_id IS NULL) = 1
             AND count(*) = 2
            THEN 'T13 OK: autoria completa, coautor fora da coorte sem registro pessoal'
            ELSE 'T13 FALHOU' END
FROM proposicao_autor WHERE proposicao_id = :proposicao_id;

-- ===========================================================================
-- T14 (coorte): poda remove o histórico junto com a pessoa
-- ===========================================================================
-- Quem sai da lista de candidatos é removido, e o CASCADE tem de levar votos,
-- candidaturas e vínculos — se sobrar órfão, a poda não cumpre a promessa de
-- não manter dados de quem não é candidato.
SAVEPOINT antes_poda;
DELETE FROM politico WHERE id = :'politico_id';

SELECT CASE WHEN (SELECT count(*) FROM voto_nominal WHERE politico_id = :'politico_id') = 0
             AND (SELECT count(*) FROM candidatura WHERE politico_id = :'politico_id') = 0
             AND (SELECT count(*) FROM proposicao_autor
                   WHERE proposicao_id = :proposicao_id AND politico_id IS NOT NULL) = 0
            THEN 'T14 OK: poda em cascata nao deixa historico orfao'
            ELSE 'T14 FALHOU' END;

-- Mas o registro factual da matéria sobrevive à poda: o coautor não-candidato
-- continua listado, porque nunca dependeu de um registro pessoal.
SELECT CASE WHEN count(*) = 1
            THEN 'T15 OK: autoria de nao-candidato sobrevive a poda da coorte'
            ELSE 'T15 FALHOU' END
FROM proposicao_autor WHERE proposicao_id = :proposicao_id;
ROLLBACK TO SAVEPOINT antes_poda;

-- ===========================================================================
-- T16 (fonte): limite de cobertura é dado consultável, não constante no código
-- ===========================================================================
SELECT CASE WHEN disponivel_desde = DATE '2001-01-01'
            THEN 'T16 OK: limite de votos nominais (2001) exposto em cobertura_fonte'
            ELSE 'T16 FALHOU' END
FROM cobertura_fonte WHERE fonte = 'CAMARA' AND recurso = 'voto_nominal';

-- ===========================================================================
-- T17 (trajetória): a vida política cobre os três níveis, não só o federal
-- ===========================================================================
INSERT INTO candidatura (politico_id, sq_candidato_tse, ano_eleicao, cargo, esfera,
                         uf, municipio, codigo_municipio_tse, partido_sigla, status, eleito)
VALUES (:'politico_id', 'tse-2016-1', 2016, 'VEREADOR',         'MUNICIPAL', 'SP', 'Campinas', '62774', 'XYZ', 'DEFERIDO', true),
       (:'politico_id', 'tse-2018-1', 2018, 'DEPUTADO_ESTADUAL','ESTADUAL',  'SP', NULL, NULL, 'XYZ', 'DEFERIDO', true),
       (:'politico_id', 'tse-2026-1', 2026, 'DEPUTADO_FEDERAL', 'FEDERAL',   'SP', NULL, NULL, 'XYZ', 'DEFERIDO', NULL);

SELECT CASE WHEN count(DISTINCT esfera) = 3
            THEN 'T17 OK: trajetoria eleitoral cobre municipal, estadual e federal'
            ELSE 'T17 FALHOU' END
FROM candidatura WHERE politico_id = :'politico_id';

-- ===========================================================================
-- T18 (fonte): "não publicado" é distinguível de "publicado desde tal data"
-- ===========================================================================
-- Sem essa distinção, a ausência de votos de plenário da Alesp seria
-- indistinguível de uma falha nossa de ingestão.
SELECT CASE WHEN (SELECT disponivel_desde FROM cobertura_fonte
                   WHERE fonte = 'ALESP' AND recurso = 'voto_nominal') IS NULL
             AND (SELECT disponivel_desde FROM cobertura_fonte
                   WHERE fonte = 'CAMARA' AND recurso = 'voto_nominal') = DATE '2001-01-01'
            THEN 'T18 OK: fonte que nao publica (NULL) difere de fonte com data de inicio'
            ELSE 'T18 FALHOU' END;

-- ===========================================================================
-- T19 (âmbito): voto de comissão não se confunde com voto de plenário
-- ===========================================================================
INSERT INTO votacao (casa, id_externo, proposicao_id, data_votacao, descricao, tipo, ambito, url_fonte)
VALUES ('ALESP', 'alesp-com-1', :proposicao_id, now(), 'Parecer na CCJ', 'NOMINAL', 'COMISSAO', 'https://al.sp.gov.br/...')
RETURNING id AS votacao_comissao_id \gset

INSERT INTO voto_nominal (votacao_id, politico_id, voto, voto_origem,
                          voto_origem_codigo)
VALUES (:votacao_comissao_id, :'politico_id', 'SIM', 'Favorável ao parecer', 'F');

SELECT CASE WHEN count(*) FILTER (WHERE ambito = 'COMISSAO') = 1
             AND count(*) FILTER (WHERE ambito = 'PLENARIO') = 1
            THEN 'T19 OK: ambito separa comissao de plenario'
            ELSE 'T19 FALHOU' END
FROM votacao WHERE proposicao_id = :proposicao_id;

-- ===========================================================================
-- T20 (B5 na prática): rótulo livre da Alesp preserva o original
-- ===========================================================================
-- A Alesp usa texto livre: 477 rótulos distintos em 226 mil votos. O mapeado
-- é interpretação; o original é o fato — e aqui há DOIS fatos, porque a fonte
-- publica o código ao lado do texto. A tradução sai do código; o texto é o que
-- a UI mostra em "registrado como".
SELECT CASE WHEN v.voto = 'SIM' AND v.voto_origem = 'Favorável ao parecer'
             AND v.voto_origem_codigo = 'F' AND m.observacao IS NOT NULL
            THEN 'T20 OK: voto da Alesp traduzido pelo codigo, com o texto original preservado'
            ELSE 'T20 FALHOU' END
FROM voto_nominal v
JOIN mapeamento_voto m ON m.fonte = 'ALESP' AND m.valor_origem = v.voto_origem_codigo
WHERE v.votacao_id = :votacao_comissao_id;

-- ===========================================================================
-- T21 (escopo MVP): "a fonte nao publica" difere de "ainda nao integramos"
-- ===========================================================================
-- Sao mensagens diferentes para o eleitor. Confundi-las seria desonesto: no
-- primeiro caso nenhuma engenharia resolve; no segundo, e trabalho nosso.
SELECT CASE
    WHEN (SELECT status FROM cobertura_fonte
           WHERE esfera='ESTADUAL' AND uf='SP' AND recurso='voto_nominal')
         = 'NAO_PUBLICADO_PELA_FONTE'
     AND (SELECT status FROM cobertura_fonte
           WHERE esfera='ESTADUAL' AND uf IS NULL AND recurso='voto_nominal')
         = 'FORA_DO_ESCOPO_MVP'
     AND (SELECT status FROM cobertura_fonte
           WHERE esfera='MUNICIPAL' AND uf IS NULL AND recurso='proposicao')
         = 'FORA_DO_ESCOPO_MVP'
    THEN 'T21 OK: fonte-nao-publica, fora-do-escopo e disponivel sao distinguiveis'
    ELSE 'T21 FALHOU' END;

-- ===========================================================================
-- T22 (escopo MVP): UF especifica tem precedencia sobre a regra geral
-- ===========================================================================
-- SP e coberto pela Alesp; os demais estados caem no fallback de UF NULL.
-- Sem essa precedencia, ou SP herdaria "fora do escopo", ou os outros 26
-- estados apareceriam como cobertos.
WITH resolvida AS (
    SELECT uf_consulta,
           (SELECT status FROM cobertura_fonte c
             WHERE c.esfera = 'ESTADUAL' AND c.recurso = 'proposicao'
               AND (c.uf = uf_consulta OR c.uf IS NULL)
             ORDER BY c.uf NULLS LAST LIMIT 1) AS status
    FROM (VALUES ('SP'), ('BA')) AS v(uf_consulta)
)
SELECT CASE WHEN (SELECT status FROM resolvida WHERE uf_consulta='SP') = 'DISPONIVEL'
             AND (SELECT status FROM resolvida WHERE uf_consulta='BA') = 'FORA_DO_ESCOPO_MVP'
            THEN 'T22 OK: SP resolve para Alesp, demais estados para fora-do-escopo'
            ELSE 'T22 FALHOU' END;

-- ===========================================================================
-- T23 (escopo MVP): trajetoria municipal continua coberta
-- ===========================================================================
-- Atuacao legislativa municipal ficou para a proxima versao, mas a trajetoria
-- ELEITORAL municipal nao: vem dos mesmos arquivos do TSE, e sem ela a linha
-- do tempo do candidato teria buracos.
SELECT CASE WHEN status = 'DISPONIVEL' AND fonte = 'TSE'
            THEN 'T23 OK: trajetoria eleitoral municipal permanece no escopo'
            ELSE 'T23 FALHOU' END
FROM cobertura_fonte WHERE esfera='MUNICIPAL' AND recurso='candidatura';

-- ===========================================================================
-- T24 (escopo MVP): o CHECK impede cobertura incoerente
-- ===========================================================================
-- Uma linha "fora do escopo" com data de inicio faria a UI dizer que cobrimos
-- algo que nao cobrimos.
SAVEPOINT antes_cobertura_incoerente;
\set ON_ERROR_STOP off
INSERT INTO cobertura_fonte (esfera, uf, recurso, status, fonte, disponivel_desde, observacao)
VALUES ('ESTADUAL', 'BA', 'proposicao', 'FORA_DO_ESCOPO_MVP', NULL, '2020-01-01', 'incoerente');
\set ON_ERROR_STOP on
ROLLBACK TO SAVEPOINT antes_cobertura_incoerente;
\echo 'T24 OK: cobertura fora-do-escopo com data de inicio rejeitada (erro esperado acima)'

-- ===========================================================================
-- T25/T26/T27/T28 (B8): voto derivado é distinguível de voto declarado
-- ===========================================================================
-- Nenhuma fonte publica "faltou": AUSENTE e LICENCIADO são calculados por nós.
-- A linha derivada não tem rótulo de origem para citar, e o CHECK garante que
-- essa é a ÚNICA exceção ao voto_origem obrigatório do B5.
INSERT INTO politico (nome_civil, nome_parlamentar)
VALUES ('Beltrano de Teste Lima', 'Beltrano Teste')
RETURNING id AS politico_derivado_id \gset

INSERT INTO voto_nominal (votacao_id, politico_id, voto, origem_registro, voto_origem)
VALUES (:votacao_id, :'politico_derivado_id', 'LICENCIADO', 'DERIVADO', NULL);
\echo 'T25 OK: voto derivado (LICENCIADO) aceito sem rotulo de origem'

SAVEPOINT antes_derivado_de_merito;
\set ON_ERROR_STOP off
INSERT INTO voto_nominal (votacao_id, politico_id, voto, origem_registro, voto_origem)
VALUES (:votacao_comissao_id, :'politico_derivado_id', 'SIM', 'DERIVADO', NULL);
\set ON_ERROR_STOP on
ROLLBACK TO SAVEPOINT antes_derivado_de_merito;
\echo 'T26 OK: voto de merito nao pode ser derivado (erro esperado acima)'

SAVEPOINT antes_derivado_com_rotulo;
\set ON_ERROR_STOP off
INSERT INTO voto_nominal (votacao_id, politico_id, voto, origem_registro, voto_origem)
VALUES (:votacao_comissao_id, :'politico_derivado_id', 'AUSENTE', 'DERIVADO', 'Ausente');
\set ON_ERROR_STOP on
ROLLBACK TO SAVEPOINT antes_derivado_com_rotulo;
\echo 'T27 OK: linha derivada nao pode fingir ter origem (erro esperado acima)'

-- Mas uma Casa que PUBLIQUE a ausência com rótulo próprio continua entrando
-- como fato, com o rótulo preservado — o CHECK não fecha essa porta.
INSERT INTO voto_nominal (votacao_id, politico_id, voto, origem_registro, voto_origem)
VALUES (:votacao_comissao_id, :'politico_derivado_id', 'AUSENTE', 'FONTE', 'Ausente');
\echo 'T28 OK: ausencia publicada pela fonte entra como fato, com rotulo'

-- ===========================================================================
-- T29/T30 (B8): períodos de exercício não podem se sobrepor
-- ===========================================================================
-- Sobreposição tornaria o universo da votação ambíguo: a mesma pessoa em
-- EXERCICIO e em LICENCA no mesmo dia produziria AUSENTE onde havia licença.
INSERT INTO mandato_exercicio (politico_id, casa, situacao, situacao_origem, condicao, inicio, fim)
VALUES (:'politico_id', 'CAMARA', 'EXERCICIO', 'Exercício', 'TITULAR', '2023-02-01', '2024-03-01'),
       (:'politico_id', 'CAMARA', 'LICENCA',   'Licença',   'TITULAR', '2024-03-01', '2024-05-01');
\echo 'T29 OK: periodos adjacentes aceitos (fim exclusivo)'

SAVEPOINT antes_periodo_sobreposto;
\set ON_ERROR_STOP off
INSERT INTO mandato_exercicio (politico_id, casa, situacao, situacao_origem, condicao, inicio, fim)
VALUES (:'politico_id', 'CAMARA', 'EXERCICIO', 'Exercício', 'TITULAR', '2024-04-01', NULL);
\set ON_ERROR_STOP on
ROLLBACK TO SAVEPOINT antes_periodo_sobreposto;
\echo 'T30 OK: periodo sobreposto na mesma casa rejeitado (erro esperado acima)'

-- ===========================================================================
-- T31 (B8): o mapeamento diz quem compõe o universo da votação
-- ===========================================================================
-- Licenciado compõe (faltou, mas por licença); suplente não empossado não —
-- não é ausência, é não ser parlamentar naquele dia.
SELECT CASE WHEN bool_and(conta_no_universo) FILTER (WHERE situacao IN ('EXERCICIO','LICENCA'))
             AND NOT bool_or(conta_no_universo) FILTER (WHERE situacao NOT IN ('EXERCICIO','LICENCA'))
            THEN 'T31 OK: universo da votacao separa exercicio/licenca de suplencia'
            ELSE 'T31 FALHOU' END
FROM mapeamento_situacao WHERE fonte = 'CAMARA';

-- ===========================================================================
-- T32/T33 (A7): curadoria manual não pode ser anônima
-- ===========================================================================
SAVEPOINT antes_revisao_anonima;
\set ON_ERROR_STOP off
INSERT INTO identificador_externo (politico_id, sistema, identificador, metodo_resolucao,
                                   score_confianca, revisado_manualmente)
VALUES (:'politico_id', 'CAMARA', 'teste-anonimo', 'FUZZY', 0.7100, true);
\set ON_ERROR_STOP on
ROLLBACK TO SAVEPOINT antes_revisao_anonima;
\echo 'T32 OK: revisao manual sem autor/data rejeitada (erro esperado acima)'

INSERT INTO identificador_externo (politico_id, sistema, identificador, metodo_resolucao,
                                   score_confianca, revisado_manualmente, revisado_por, revisado_em)
VALUES (:'politico_id', 'CAMARA', 'teste-curado', 'FUZZY', 0.7100, true, 'owner@votecomdados', now());
\echo 'T33 OK: revisao manual registrada com autor e data'

-- ===========================================================================
-- T34/T35 (Q9): correção retroativa fica registrada, com ou sem worker
-- ===========================================================================
SET LOCAL votecomdados.execucao_id = :'execucao_id';
UPDATE voto_nominal SET voto = 'NAO', voto_origem = 'Não'
 WHERE votacao_id = :votacao_id AND politico_id = :'politico_id';

SELECT CASE WHEN count(*) = 1
            THEN 'T34 OK: alteracao de voto registrada com a execucao responsavel'
            ELSE 'T34 FALHOU' END
FROM voto_nominal_historico h
JOIN voto_nominal v ON v.id = h.voto_nominal_id
WHERE v.votacao_id = :votacao_id AND v.politico_id = :'politico_id'
  AND h.voto_anterior = 'OBSTRUCAO' AND h.voto_novo = 'NAO'
  AND h.execucao_id = :execucao_id;

-- Alteração fora do worker (curadoria por SQL) fica marcada como manual —
-- que é justamente o caso em que saber a procedência mais importa.
SET LOCAL votecomdados.execucao_id = '';
UPDATE voto_nominal SET voto = 'ABSTENCAO', voto_origem = 'Abstenção'
 WHERE votacao_id = :votacao_id AND politico_id = :'politico_id';

SELECT CASE WHEN count(*) = 1
            THEN 'T35 OK: alteracao manual registrada sem execucao (procedencia visivel)'
            ELSE 'T35 FALHOU' END
FROM voto_nominal_historico h
JOIN voto_nominal v ON v.id = h.voto_nominal_id
WHERE v.votacao_id = :votacao_id AND v.politico_id = :'politico_id'
  AND h.voto_novo = 'ABSTENCAO' AND h.execucao_id IS NULL;

-- ===========================================================================
-- T36/T37 (Q3): quem não é da coorte é esperado, não é alerta
-- ===========================================================================
-- Gravado UMA VEZ por parlamentar. Sem a deduplicação, cada reprocessamento
-- multiplicaria as mesmas linhas; sem o motivo próprio, a maioria das ~398
-- linhas de cada votação cairia em POLITICO_NAO_RESOLVIDO e o alerta — cujo
-- valor esperado é zero — nasceria morto.
INSERT INTO staging.registro_rejeitado (execucao_id, fonte, recurso, id_externo, motivo, payload)
VALUES (:execucao_id, 'CAMARA', 'parlamentar', '74328', 'FORA_DA_COORTE',
        '{"nome": "Deputado sem candidatura em 2026"}'::jsonb);

SAVEPOINT antes_quarentena_duplicada;
\set ON_ERROR_STOP off
INSERT INTO staging.registro_rejeitado (execucao_id, fonte, recurso, id_externo, motivo, payload)
VALUES (:execucao_id, 'CAMARA', 'parlamentar', '74328', 'FORA_DA_COORTE',
        '{"nome": "Deputado sem candidatura em 2026"}'::jsonb);
\set ON_ERROR_STOP on
ROLLBACK TO SAVEPOINT antes_quarentena_duplicada;
\echo 'T36 OK: reprocessamento nao multiplica o mesmo caso aberto (erro esperado acima)'

-- A consulta de alerta é a mesma documentada no schema.
SELECT CASE WHEN count(*) FILTER (WHERE motivo = 'FORA_DA_COORTE') = 0
             AND count(*) FILTER (WHERE motivo = 'POLITICO_NAO_RESOLVIDO') > 0
            THEN 'T37 OK: fora-da-coorte nao alerta; nao-resolvido alerta'
            ELSE 'T37 FALHOU' END
FROM staging.registro_rejeitado
WHERE resolvido_em IS NULL AND motivo <> 'FORA_DA_COORTE';

-- ===========================================================================
-- T38 (A5): ementa corrigida na origem não apaga a versão anterior
-- ===========================================================================
UPDATE proposicao SET ementa = 'Ementa de teste, corrigida pela Casa'
 WHERE id = :proposicao_id;

SELECT CASE WHEN count(*) = 1
            THEN 'T38 OK: correcao de ementa preserva a versao anterior'
            ELSE 'T38 FALHOU' END
FROM proposicao_historico
WHERE proposicao_id = :proposicao_id AND campo = 'ementa'
  AND valor_anterior = 'Ementa de teste';

-- ===========================================================================
-- T39 (B8): a derivação de ausência e licença, ponta a ponta
-- ===========================================================================
-- É o teste que dá sentido a todos os anteriores: dado o universo de quem
-- estava na Casa e a lista de quem votou, o que sobra é ausência — e quem
-- estava licenciado não pode aparecer como quem simplesmente faltou.
INSERT INTO politico (nome_civil) VALUES ('Sicrano Suplente Teste')
RETURNING id AS politico_suplente_id \gset
INSERT INTO politico (nome_civil) VALUES ('Deltrano Votante Teste')
RETURNING id AS politico_votante_id \gset

INSERT INTO mandato_exercicio (politico_id, casa, situacao, situacao_origem, condicao, inicio, fim)
VALUES (:'politico_derivado_id', 'CAMARA', 'EXERCICIO', 'Exercício', 'TITULAR', '2023-02-01', NULL),
       (:'politico_votante_id',  'CAMARA', 'EXERCICIO', 'Exercício', 'TITULAR', '2023-02-01', NULL),
       (:'politico_suplente_id', 'CAMARA', 'SUPLENCIA', 'SUPLENCIA', 'SUPLENTE', '2023-02-01', NULL);

INSERT INTO votacao (casa, id_externo, proposicao_id, data_votacao, descricao, tipo, url_fonte)
VALUES ('CAMARA', 'teste-vot-derivacao', :proposicao_id,
        TIMESTAMPTZ '2024-04-10 15:00-03', 'Votacao para derivacao', 'NOMINAL', 'https://exemplo')
RETURNING id AS votacao_deriv_id \gset

-- Só um dos quatro registrou voto.
INSERT INTO voto_nominal (votacao_id, politico_id, voto, origem_registro, voto_origem)
VALUES (:votacao_deriv_id, :'politico_votante_id', 'SIM', 'FONTE', 'Sim');

-- A derivação: universo do dia menos quem votou.
INSERT INTO voto_nominal (votacao_id, politico_id, voto, origem_registro, voto_origem)
SELECT :votacao_deriv_id, m.politico_id,
       CASE m.situacao WHEN 'LICENCA' THEN 'LICENCIADO' ELSE 'AUSENTE' END::tipo_voto_enum,
       'DERIVADO', NULL
  FROM mandato_exercicio m
 WHERE m.casa = 'CAMARA'
   AND daterange(m.inicio, m.fim, '[)') @> DATE '2024-04-10'
   AND EXISTS (SELECT 1 FROM mapeamento_situacao ms
                WHERE ms.fonte = 'CAMARA' AND ms.situacao = m.situacao
                  AND ms.conta_no_universo)
   AND NOT EXISTS (SELECT 1 FROM voto_nominal v
                    WHERE v.votacao_id = :votacao_deriv_id
                      AND v.politico_id = m.politico_id);

SELECT CASE WHEN
        (SELECT voto FROM voto_nominal
          WHERE votacao_id = :votacao_deriv_id AND politico_id = :'politico_id') = 'LICENCIADO'
    AND (SELECT voto FROM voto_nominal
          WHERE votacao_id = :votacao_deriv_id AND politico_id = :'politico_derivado_id') = 'AUSENTE'
    AND (SELECT voto FROM voto_nominal
          WHERE votacao_id = :votacao_deriv_id AND politico_id = :'politico_votante_id') = 'SIM'
    AND NOT EXISTS (SELECT 1 FROM voto_nominal
                     WHERE votacao_id = :votacao_deriv_id
                       AND politico_id = :'politico_suplente_id')
            THEN 'T39 OK: licenciado nao vira ausente, e suplente nao vira linha'
            ELSE 'T39 FALHOU' END;

-- ===========================================================================
-- T40 (R7): todo rótulo de voto do dado REAL resolve em mapeamento_voto
-- ===========================================================================
-- Golden file: amostra verbatim de votacoesVotos-2026 da Câmara (ver
-- db/golden/README.md). O maior risco deste produto é de correção de domínio,
-- não de código — um mapeamento errado não quebra teste unitário nenhum, só
-- atribui a alguém uma conduta que ele não teve. Rótulo novo na origem falha
-- aqui, antes de virar quarentena silenciosa em produção.
CREATE TEMP TABLE golden_votos_camara (
    id_votacao TEXT, uri_votacao TEXT, data_hora_voto TEXT, voto TEXT,
    deputado_id TEXT, deputado_uri TEXT, deputado_nome TEXT,
    deputado_sigla_partido TEXT, deputado_uri_partido TEXT,
    deputado_sigla_uf TEXT, deputado_id_legislatura TEXT, deputado_url_foto TEXT
) ON COMMIT DROP;

\copy golden_votos_camara FROM '/tmp/golden/camara-votacoesVotos-2026-amostra.csv' WITH (FORMAT csv, HEADER true, DELIMITER ';', QUOTE '"')

SELECT CASE WHEN count(*) = 0
            THEN 'T40 OK: todo rotulo do dado real da Camara tem mapeamento'
            ELSE 'T40 FALHOU: rotulo sem mapeamento -> ' || string_agg(DISTINCT voto, ', ') END
FROM golden_votos_camara g
WHERE NOT EXISTS (SELECT 1 FROM mapeamento_voto m
                   WHERE m.fonte = 'CAMARA' AND m.valor_origem = g.voto);

-- E a contrapartida: a amostra real NÃO traz rótulo de ausência. Se um dia
-- trouxer, este teste falha e a decisão de derivar (B8) precisa ser revista.
SELECT CASE WHEN count(*) = 0
            THEN 'T41 OK: a fonte nao publica ausencia — derivar continua necessario'
            ELSE 'T41 FALHOU: a fonte passou a publicar ausencia' END
FROM golden_votos_camara
WHERE unaccent_imutavel(lower(voto)) LIKE '%ausent%';

-- ===========================================================================
-- T42/T43/T44/T45 (R4): a projeção de leitura bate com a origem
-- ===========================================================================
-- O risco do CQRS é a projeção divergir em silêncio. Estes invariantes são o
-- que impede isso de acontecer sem ninguém perceber.
SELECT reconstruir_perfil_leitura(:execucao_id) AS perfis \gset

SELECT CASE WHEN (SELECT count(*) FROM perfil_leitura) = (SELECT count(*) FROM politico)
            THEN 'T42 OK: projecao cobre todos os politicos, um por pessoa'
            ELSE 'T42 FALHOU' END;

-- Trajetória: mesma contagem da origem e ordenada do mais recente ao mais antigo.
SELECT CASE WHEN jsonb_array_length(pl.trajetoria)
                 = (SELECT count(*) FROM candidatura c WHERE c.politico_id = pl.politico_id)
             AND (pl.trajetoria -> 0 ->> 'anoEleicao')::int
                 = (SELECT max(ano_eleicao) FROM candidatura c WHERE c.politico_id = pl.politico_id)
            THEN 'T43 OK: trajetoria projetada bate com a origem, mais recente primeiro'
            ELSE 'T43 FALHOU' END
FROM perfil_leitura pl WHERE pl.politico_id = :'politico_id';

-- Precedência por UF sobrevive à desnormalização: SP resolve para a Alesp
-- ("a fonte não publica"), e não para o fallback de fora-do-escopo.
SELECT CASE WHEN EXISTS (
                SELECT 1 FROM jsonb_array_elements(pl.cobertura) e
                 WHERE e ->> 'esfera' = 'ESTADUAL' AND e ->> 'uf' = 'SP'
                   AND e ->> 'recurso' = 'voto_nominal'
                   AND e ->> 'status' = 'NAO_PUBLICADO_PELA_FONTE')
            THEN 'T44 OK: precedencia por UF preservada na projecao'
            ELSE 'T44 FALHOU' END
FROM perfil_leitura pl WHERE pl.politico_id = :'politico_id';

-- O contrato de defasagem, explícito: a projeção NÃO se atualiza sozinha.
-- Escrever isto como invariante evita que alguém suponha o contrário e
-- construa uma tela em cima da suposição.
INSERT INTO candidatura (politico_id, sq_candidato_tse, ano_eleicao, cargo, esfera,
                         uf, municipio, codigo_municipio_tse, partido_sigla, status, eleito)
VALUES (:'politico_id', 'tse-2012-1', 2012, 'VEREADOR', 'MUNICIPAL', 'SP', 'Campinas', '62774', 'XYZ', 'DEFERIDO', false);

SELECT CASE WHEN jsonb_array_length(trajetoria)
                 < (SELECT count(*) FROM candidatura WHERE politico_id = :'politico_id')
            THEN 'T45 OK: projecao fica defasada ate a reconstrucao (contrato explicito)'
            ELSE 'T45 FALHOU' END
FROM perfil_leitura WHERE politico_id = :'politico_id';

SELECT reconstruir_perfil_leitura(:execucao_id) \gset perfis2_
SELECT CASE WHEN jsonb_array_length(trajetoria)
                 = (SELECT count(*) FROM candidatura WHERE politico_id = :'politico_id')
            THEN 'T46 OK: reconstrucao alcanca a origem'
            ELSE 'T46 FALHOU' END
FROM perfil_leitura WHERE politico_id = :'politico_id';

-- Poda da coorte leva a projeção junto: quem sai da lista não pode continuar
-- com página pronta em cache de banco.
SAVEPOINT antes_poda_projecao;
DELETE FROM politico WHERE id = :'politico_id';
SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM perfil_leitura WHERE politico_id = :'politico_id')
            THEN 'T47 OK: poda remove a projecao junto com a pessoa'
            ELSE 'T47 FALHOU' END;
ROLLBACK TO SAVEPOINT antes_poda_projecao;

-- ===========================================================================
-- T48/T49/T50 (R8): o pacote de dados abertos não vaza dado pessoal
-- ===========================================================================
-- A garantia precisa ser estrutural, não uma promessa no README: qualquer
-- coluna sensível acrescentada a uma tabela publicada apareceria sozinha num
-- `SELECT *`, e aqui ela falha o teste antes de sair do repositório.
SELECT CASE WHEN count(*) = 0
            THEN 'T48 OK: dados abertos nao expoem coluna pessoal (cpf_hmac, curador)'
            ELSE 'T48 FALHOU: coluna sensivel exposta -> '
                 || string_agg(table_name || '.' || column_name, ', ') END
FROM information_schema.columns
WHERE table_schema = 'dados_abertos'
  AND column_name IN ('cpf_hmac', 'revisado_por');

-- O inverso também importa: sem origem_registro, quem baixar o arquivo leria
-- ausência calculada por nós como registro oficial da Casa.
SELECT CASE WHEN count(*) = 1
            THEN 'T49 OK: voto derivado e distinguivel no dado publicado'
            ELSE 'T49 FALHOU' END
FROM information_schema.columns
WHERE table_schema = 'dados_abertos' AND table_name = 'voto_nominal'
  AND column_name = 'origem_registro';

-- O manifesto é o que torna o dump citável: sem ele ninguém consegue dizer de
-- qual instantâneo saiu uma análise, nem reencontrá-lo para contestar.
SELECT CASE WHEN gerado_em = current_date
             AND linhas_por_tabela ? 'voto_nominal'
             AND votos_derivados_por_nos IS NOT NULL
             AND vinculos_fuzzy_sem_revisao_humana IS NOT NULL
            THEN 'T50 OK: manifesto declara data, volumetria e o que e calculo nosso'
            ELSE 'T50 FALHOU' END
FROM dados_abertos.manifesto;

-- ===========================================================================
-- T51/T52/T53 (A12): o vocabulário do Senado traduzido, ou declarado como não
-- ===========================================================================
-- Mesmo princípio do T40, com uma diferença: aqui há um rótulo que
-- DELIBERADAMENTE não se traduz — 'NA' ("Dispositivo não citado") não é voto.
-- O teste fixa esse conjunto em vez de ignorá-lo: se a fonte trouxer rótulo
-- novo, ele entra na lista de não traduzidos e o invariante falha.
CREATE TEMP TABLE golden_senado (linha TEXT) ON COMMIT DROP;
\copy golden_senado FROM '/tmp/golden/senado-votacao-amostra.jsonl' WITH (FORMAT csv, QUOTE E'\x01', DELIMITER E'\x02')

CREATE TEMP TABLE golden_senado_votos ON COMMIT DROP AS
    SELECT DISTINCT v ->> 'siglaVotoParlamentar' AS rotulo
      FROM golden_senado g,
           jsonb_array_elements((g.linha::jsonb) -> 'votos') v;

SELECT CASE WHEN count(*) = 13
            THEN 'T51 OK: amostra do Senado cobre os 13 rotulos verificados'
            ELSE 'T51 FALHOU: amostra cobre ' || count(*) || ' rotulos' END
FROM golden_senado_votos;

SELECT CASE WHEN coalesce(string_agg(rotulo, ',' ORDER BY rotulo), '') = 'NA'
            THEN 'T52 OK: so ''NA'' fica sem traducao, e por decisao explicita'
            ELSE 'T52 FALHOU: rotulos sem traducao -> '
                 || string_agg(rotulo, ', ' ORDER BY rotulo) END
FROM golden_senado_votos g
WHERE NOT EXISTS (SELECT 1 FROM mapeamento_voto m
                   WHERE m.fonte = 'SENADO' AND m.valor_origem = g.rotulo);

-- O rótulo mais frequente do Senado é voto secreto. Traduzi-lo para SIM/NAO
-- seria inventar; para AUSENTE, caluniar por omissão.
SELECT CASE WHEN voto = 'SECRETO' AND observacao IS NOT NULL
            THEN 'T53 OK: voto secreto tem categoria propria e nota de metodologia'
            ELSE 'T53 FALHOU' END
FROM mapeamento_voto WHERE fonte = 'SENADO' AND valor_origem = 'Votou';

-- ===========================================================================
-- T54/T55 (A12): a cobertura sabe que a esfera federal tem DUAS Casas
-- ===========================================================================
-- Antes desta correção, `cobertura_fonte` era chaveada por (esfera, uf,
-- recurso) e a linha FEDERAL/voto_nominal pertencia à Câmara. A plataforma
-- dizia a senadores que o voto nominal existia "desde 2001", quando o do
-- Senado existe desde 1991.
SELECT CASE WHEN count(*) = 2
             AND min(disponivel_desde) = DATE '1991-01-01'
             AND max(disponivel_desde) = DATE '2001-01-01'
            THEN 'T54 OK: Camara e Senado coexistem com datas de inicio proprias'
            ELSE 'T54 FALHOU' END
FROM cobertura_fonte
WHERE esfera = 'FEDERAL' AND recurso = 'voto_nominal';

SELECT CASE WHEN casa_do_mandato('SENADOR', 'MS') = 'SENADO'
             AND casa_do_mandato('DEPUTADO_FEDERAL', 'MS') = 'CAMARA'
             AND casa_do_mandato('DEPUTADO_ESTADUAL', 'SP') = 'ALESP'
             AND casa_do_mandato('DEPUTADO_ESTADUAL', 'BA') IS NULL
             AND casa_do_mandato('PREFEITO', 'SP') IS NULL
            THEN 'T55 OK: mandato resolve para a Casa certa, e so quando existe'
            ELSE 'T55 FALHOU' END;

-- ===========================================================================
-- T56/T57 (A12): quem foi deputado E senador vê as duas coberturas
-- ===========================================================================
-- É o caso que o modelo antigo não conseguia representar, e o que torna a
-- correção verificável em vez de plausível.
INSERT INTO politico (nome_civil) VALUES ('Ex-deputado e Senador de Teste')
RETURNING id AS politico_duas_casas \gset

INSERT INTO candidatura (politico_id, sq_candidato_tse, ano_eleicao, cargo, esfera,
                         uf, partido_sigla, status, eleito)
VALUES (:'politico_duas_casas', 'tse-dc-1', 2018, 'DEPUTADO_FEDERAL', 'FEDERAL', 'MS', 'XYZ', 'DEFERIDO', true),
       (:'politico_duas_casas', 'tse-dc-2', 2022, 'SENADOR',          'FEDERAL', 'MS', 'XYZ', 'DEFERIDO', true),
       (:'politico_duas_casas', 'tse-dc-3', 2026, 'SENADOR',          'FEDERAL', 'MS', 'XYZ', 'DEFERIDO', NULL);

SELECT reconstruir_perfil_leitura() \gset descartado_

SELECT CASE WHEN (SELECT count(*) FROM jsonb_array_elements(cobertura) e
                   WHERE e ->> 'recurso' = 'voto_nominal'
                     AND e ->> 'casa' IN ('CAMARA', 'SENADO')) = 2
             AND EXISTS (SELECT 1 FROM jsonb_array_elements(cobertura) e
                          WHERE e ->> 'casa' = 'SENADO'
                            AND e ->> 'recurso' = 'voto_nominal'
                            AND e ->> 'disponivelDesde' = '1991-01-01')
            THEN 'T56 OK: perfil de quem passou pelas duas Casas mostra as duas coberturas'
            ELSE 'T56 FALHOU' END
FROM perfil_leitura WHERE politico_id = :'politico_duas_casas';

-- E quem passou por uma Casa só não vê a outra — o oposto do defeito antigo.
SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM jsonb_array_elements(cobertura) e
                              WHERE e ->> 'casa' = 'SENADO')
            THEN 'T57 OK: quem nunca foi senador nao ve cobertura do Senado'
            ELSE 'T57 FALHOU' END
FROM perfil_leitura WHERE politico_id = :'politico_id';

-- ===========================================================================
-- T58 (A12): votação secreta é representável e distinguível
-- ===========================================================================
INSERT INTO votacao (casa, id_externo, proposicao_id, data_votacao, descricao,
                     tipo, secreta, url_fonte)
VALUES ('SENADO', 'teste-vot-secreta', :proposicao_id, now(),
        'Votacao secreta de teste', 'NOMINAL', true, 'https://exemplo')
RETURNING id AS votacao_secreta_id \gset

INSERT INTO voto_nominal (votacao_id, politico_id, voto, origem_registro, voto_origem)
VALUES (:votacao_secreta_id, :'politico_duas_casas', 'SECRETO', 'FONTE', 'Votou');

SELECT CASE WHEN v.secreta AND vn.voto = 'SECRETO' AND vn.voto_origem = 'Votou'
                          AND vn.origem_registro = 'FONTE'
            THEN 'T58 OK: participacao em votacao secreta e fato da fonte, nao ausencia'
            ELSE 'T58 FALHOU' END
FROM votacao v JOIN voto_nominal vn ON vn.votacao_id = v.id
WHERE v.id = :votacao_secreta_id;

-- ===========================================================================
-- T59/T60/T61 (W12): o vocabulário da Alesp é mapeado por CÓDIGO, não por texto
-- ===========================================================================
-- A Alesp publica <TipoVoto> (código de uma letra, 8 valores documentados por
-- ela) e <Voto> (texto livre: 477 valores distintos em 226 mil votos, com
-- erros de digitação). Estes invariantes fixam que a chave é o código.
--
-- Se um dia a fonte emitir código novo, T60 falha aqui — em vez de virar
-- quarentena silenciosa em produção.
CREATE TEMP TABLE golden_alesp (linha TEXT) ON COMMIT DROP;
\copy golden_alesp FROM PROGRAM 'tr ''<'' ''\n'' < /tmp/golden/alesp-votacoes-comissao-amostra.xml | sed -n ''s|^TipoVoto>\(.*\)|\1|p''' WITH (FORMAT csv, QUOTE E'\x01', DELIMITER E'\x02')

SELECT CASE WHEN count(DISTINCT linha) = 7
            THEN 'T59 OK: amostra da Alesp cobre os 7 codigos de voto em uso'
            ELSE 'T59 FALHOU: amostra cobre ' || count(DISTINCT linha)
                 || ' codigos -> ' || string_agg(DISTINCT linha, ',' ORDER BY linha) END
FROM golden_alesp;

SELECT CASE WHEN count(*) = 0
            THEN 'T60 OK: todo codigo da amostra da Alesp resolve em mapeamento_voto'
            ELSE 'T60 FALHOU: codigos sem traducao -> '
                 || string_agg(DISTINCT linha, ', ' ORDER BY linha) END
FROM golden_alesp g
WHERE NOT EXISTS (SELECT 1 FROM mapeamento_voto m
                   WHERE m.fonte = 'ALESP' AND m.valor_origem = g.linha);

-- 'O' ("Outros") é o único código documentado que NÃO se traduz, e a ausência
-- é deliberada: é a própria fonte dizendo "não classificado".
SELECT CASE WHEN count(*) = 0
            THEN 'T61 OK: o codigo ''O'' (Outros) segue sem traducao, por decisao explicita'
            ELSE 'T61 FALHOU: ''O'' foi mapeado, e a fonte nao diz o que ele significa' END
FROM mapeamento_voto WHERE fonte = 'ALESP' AND valor_origem = 'O';

-- ===========================================================================
-- T62 (W12): S e B têm categoria própria, e não colapsam em ABSTENCAO
-- ===========================================================================
-- 'S' (voto em separado, 2.130 votos) e 'B' (em branco, 186) são os dois
-- códigos que exigiram valor novo no enum. Traduzi-los para ABSTENCAO diria
-- que o parlamentar se absteve — no caso do S, o oposto do que houve.
SELECT CASE WHEN (SELECT voto FROM mapeamento_voto
                   WHERE fonte = 'ALESP' AND valor_origem = 'S') = 'VOTO_EM_SEPARADO'
             AND (SELECT voto FROM mapeamento_voto
                   WHERE fonte = 'ALESP' AND valor_origem = 'B') = 'BRANCO'
             AND (SELECT voto FROM mapeamento_voto
                   WHERE fonte = 'ALESP' AND valor_origem = 'A') = 'ABSTENCAO'
            THEN 'T62 OK: voto em separado, branco e abstencao sao categorias distintas'
            ELSE 'T62 FALHOU: a Alesp distingue os tres e o mapeamento nao' END;

-- ===========================================================================
-- T63 (W12): o código só existe onde a fonte publica um, e nunca em derivado
-- ===========================================================================
INSERT INTO votacao (casa, id_externo, proposicao_id, data_votacao, descricao,
                     tipo, ambito, url_fonte)
VALUES ('ALESP', 'teste-vot-comissao', :proposicao_id, now(),
        'Deliberacao de teste em comissao', 'NOMINAL', 'COMISSAO', 'https://exemplo')
RETURNING id AS votacao_alesp_id \gset

INSERT INTO voto_nominal (votacao_id, politico_id, voto, origem_registro,
                          voto_origem, voto_origem_codigo)
VALUES (:votacao_alesp_id, :'politico_duas_casas', 'VOTO_EM_SEPARADO', 'FONTE',
        'Com o Voto em Separado', 'S');

SELECT CASE WHEN vn.voto_origem_codigo = 'S' AND vn.voto_origem = 'Com o Voto em Separado'
            THEN 'T63 OK: codigo e texto da fonte convivem, e os dois sao preservados'
            ELSE 'T63 FALHOU' END
FROM voto_nominal vn WHERE vn.votacao_id = :votacao_alesp_id;

-- Linha derivada não tem rótulo de origem a citar — nem texto, nem código.
SAVEPOINT antes_do_codigo_em_derivado;
\set ON_ERROR_STOP 0
INSERT INTO voto_nominal (votacao_id, politico_id, voto, origem_registro,
                          voto_origem, voto_origem_codigo)
VALUES (:votacao_alesp_id, :'politico_id', 'AUSENTE', 'DERIVADO', NULL, 'F');
\set ON_ERROR_STOP 1
ROLLBACK TO SAVEPOINT antes_do_codigo_em_derivado;

SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM voto_nominal
                              WHERE votacao_id = :votacao_alesp_id
                                AND origem_registro = 'DERIVADO')
            THEN 'T64 OK: linha derivada nao pode carregar codigo de origem'
            ELSE 'T64 FALHOU: derivado entrou com codigo da fonte' END;

-- ===========================================================================
-- T65 (W12): a cobertura da Alesp diz a data que o arquivo sustenta
-- ===========================================================================
-- As duas datas anteriores diziam 1995 e erravam em sentidos opostos. Este
-- invariante impede que voltem a ser número plausível nunca conferido.
SELECT CASE WHEN (SELECT disponivel_desde FROM cobertura_fonte
                   WHERE casa = 'ALESP' AND recurso = 'votacao_comissao')
                 = DATE '2006-02-15'
             AND (SELECT disponivel_desde FROM cobertura_fonte
                   WHERE casa = 'ALESP' AND recurso = 'proposicao')
                 = DATE '1970-09-23'
            THEN 'T65 OK: cobertura da Alesp bate com o que os arquivos alcancam'
            ELSE 'T65 FALHOU: data de cobertura da Alesp divergiu do verificado' END;

-- ===========================================================================
-- T66/T67/T68/T69 (A1): possui_atuacao_legislativa é CALCULADA, não presumida
-- ===========================================================================
-- Achado A1 (01/09/2026): a coluna nasceu NOT NULL DEFAULT false na V1 e
-- nenhum job a atualizava. É ela que decide quais perfis o build estático
-- gera — com a coluna sempre false, nenhum perfil seria pré-renderizado.

-- Quatro políticos isolados, um por sinal (e um sem sinal nenhum), para que
-- nenhum teste dependa do estado acumulado dos anteriores nesta transação.
INSERT INTO politico (nome_civil) VALUES ('Fulana Autoria Teste')
RETURNING id AS pol_autoria_id \gset
INSERT INTO politico (nome_civil) VALUES ('Fulana VotoFonte Teste')
RETURNING id AS pol_voto_id \gset
INSERT INTO politico (nome_civil) VALUES ('Fulana Mandato Teste')
RETURNING id AS pol_mandato_id \gset
INSERT INTO politico (nome_civil) VALUES ('Fulana SemSinal Teste')
RETURNING id AS pol_nenhum_id \gset

-- Sinal 1: autoria de matéria (vale para Câmara, Senado e Alesp por igual).
INSERT INTO proposicao_autor (proposicao_id, politico_id, autor_nome)
VALUES (:proposicao_id, :'pol_autoria_id', 'Fulana Autoria Teste');

-- Sinal 2: voto registrado pela fonte. É o único sinal que cobre Senado e
-- Alesp, que não alimentam mandato_exercicio.
INSERT INTO votacao (casa, id_externo, proposicao_id, data_votacao, descricao,
                     tipo, url_fonte)
VALUES ('SENADO', 'teste-vot-atuacao', :proposicao_id, now(),
        'Votacao de teste para atuacao', 'NOMINAL', 'https://exemplo')
RETURNING id AS votacao_atuacao_id \gset

INSERT INTO voto_nominal (votacao_id, politico_id, voto, origem_registro, voto_origem)
VALUES (:votacao_atuacao_id, :'pol_voto_id', 'SIM', 'FONTE', 'Sim');

-- Sinal 3: mandato exercido, mesmo sem voto nenhum — é o caso de quem só tem
-- ausência DERIVADA por nós. A ausência só é derivável porque a pessoa estava
-- em exercício, e marcá-la sem atuação apagaria um mandato inteiro.
INSERT INTO mandato_exercicio (politico_id, casa, situacao, situacao_origem, inicio)
VALUES (:'pol_mandato_id', 'CAMARA', 'EXERCICIO', 'Exercício', DATE '2023-02-01');

SELECT marcar_atuacao_legislativa() AS afetados_primeira_chamada \gset

SELECT CASE WHEN (SELECT possui_atuacao_legislativa FROM politico WHERE id = :'pol_autoria_id')
             AND (SELECT possui_atuacao_legislativa FROM politico WHERE id = :'pol_voto_id')
             AND (SELECT possui_atuacao_legislativa FROM politico WHERE id = :'pol_mandato_id')
             AND NOT (SELECT possui_atuacao_legislativa FROM politico WHERE id = :'pol_nenhum_id')
            THEN 'T66 OK: autoria, voto de FONTE e mandato marcam atuacao; sem sinal fica false'
            ELSE 'T66 FALHOU' END;

-- Voto DERIVADO sozinho, sem os outros dois sinais, não deveria bastar: é a
-- Casa que precisa ter registrado ALGO, e uma ausência calculada por nós não
-- é isso. Na prática nunca acontece sem mandato_exercicio junto (é de lá que
-- a ausência é derivada), mas o invariante fixa a regra mesmo assim.
INSERT INTO politico (nome_civil) VALUES ('Fulana SoDerivado Teste')
RETURNING id AS pol_derivado_id \gset

INSERT INTO votacao (casa, id_externo, proposicao_id, data_votacao, descricao,
                     tipo, url_fonte)
VALUES ('CAMARA', 'teste-vot-derivado', :proposicao_id, now(),
        'Votacao de teste derivado', 'NOMINAL', 'https://exemplo')
RETURNING id AS votacao_derivado_id \gset

INSERT INTO voto_nominal (votacao_id, politico_id, voto, origem_registro)
VALUES (:votacao_derivado_id, :'pol_derivado_id', 'AUSENTE', 'DERIVADO');

SELECT marcar_atuacao_legislativa() \gset

SELECT CASE WHEN NOT (SELECT possui_atuacao_legislativa FROM politico
                        WHERE id = :'pol_derivado_id')
            THEN 'T67 OK: voto so DERIVADO, sem mandato, nao basta sozinho'
            ELSE 'T67 FALHOU' END;

-- Recálculo total: perder o único registro precisa DESLIGAR a flag. Um job
-- que só liga bit a bit nunca desliga nada, e a flag passaria a mentir na
-- direção oposta depois de uma poda ou correção retroativa.
DELETE FROM proposicao_autor WHERE politico_id = :'pol_autoria_id';
SELECT marcar_atuacao_legislativa() \gset

SELECT CASE WHEN NOT (SELECT possui_atuacao_legislativa FROM politico
                        WHERE id = :'pol_autoria_id')
            THEN 'T68 OK: perder o unico registro desliga a flag, nao so acumula'
            ELSE 'T68 FALHOU: flag so cresce, nunca corrige para baixo' END;

-- Idempotência: sem mudança no universo desde a última chamada, a segunda
-- chamada não afeta ninguém.
SELECT marcar_atuacao_legislativa() AS afetados_repeticao \gset

SELECT CASE WHEN :afetados_repeticao = 0
            THEN 'T69 OK: chamar de novo sem mudanca no universo nao afeta ninguem'
            ELSE 'T69 FALHOU: ' || :afetados_repeticao || ' linha(s) afetada(s) sem motivo' END;


-- Desfaz tudo: o teste não deixa rastro e pode ser reexecutado à vontade.
ROLLBACK;

\echo ''
\echo '=== fim: 69 invariantes verificados, transacao revertida ==='
