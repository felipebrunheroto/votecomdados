-- ===========================================================================
-- Dados de desenvolvimento — espelham as fixtures do frontend (web/src/lib/api).
--
-- Aplicado SOMENTE com o perfil `dev` (ver application-dev.yml): em produção
-- o Flyway não enxerga esta location.
--
-- Povoado com os mesmos CASOS DIFÍCEIS das fixtures: candidato sem mandato,
-- votação simbólica, voto de comissão, obstrução, ausência, registro
-- indeferido e mandato anterior a 2001.
-- ===========================================================================

-- possui_atuacao_legislativa NÃO é semeada aqui: fica no default (false) e é
-- CALCULADA por marcar_atuacao_legislativa() mais abaixo, depois que autoria,
-- voto e mandato já foram inseridos — exatamente como o FinalizadorDeIngestao
-- faz ao fim de uma ingestão real (achado A1). Hardcoded aqui já divergiu da
-- regra uma vez: o candidato 5 tinha `true` sem nenhum voto, autoria ou
-- mandato por trás — o seed afirmava atuação que não existia.
INSERT INTO politico (id, nome_civil, nome_urna, nome_parlamentar) VALUES
  ('a1000000-0000-4000-8000-000000000001', 'Adriana Ventura Nogueira',  'Adriana Ventura',  'Adriana Ventura'),
  ('a1000000-0000-4000-8000-000000000002', 'Joana Ribeiro Alcântara',   'Joana Alcântara',  NULL),
  ('a1000000-0000-4000-8000-000000000003', 'Adilson Barroso Pinto',     'Adilson Barroso',  'Adilson Barroso'),
  ('a1000000-0000-4000-8000-000000000004', 'Adolfo Viana de Castro Neto','Adolfo Viana',    'Adolfo Viana'),
  ('a1000000-0000-4000-8000-000000000005', 'Acácio Favacho Rodrigues',  'Acácio Favacho',   'Acácio Favacho'),
  ('a1000000-0000-4000-8000-000000000006', 'Adriano Augusto do Baldy',  'Adriano do Baldy', NULL);

INSERT INTO candidatura
  (politico_id, sq_candidato_tse, ano_eleicao, cargo, esfera, uf, municipio, partido_sigla, status, eleito) VALUES
  ('a1000000-0000-4000-8000-000000000001','tse-1-2026',2026,'GOVERNADOR','ESTADUAL','SP',NULL,'NOVO','DEFERIDO',NULL),
  ('a1000000-0000-4000-8000-000000000001','tse-1-2022',2022,'DEPUTADO_FEDERAL','FEDERAL','SP',NULL,'NOVO','DEFERIDO',true),
  ('a1000000-0000-4000-8000-000000000001','tse-1-2018',2018,'DEPUTADO_ESTADUAL','ESTADUAL','SP',NULL,'NOVO','DEFERIDO',true),
  ('a1000000-0000-4000-8000-000000000001','tse-1-2016',2016,'VEREADOR','MUNICIPAL','SP','Campinas','PSDB','DEFERIDO',true),
  ('a1000000-0000-4000-8000-000000000002','tse-2-2026',2026,'DEPUTADO_FEDERAL','FEDERAL','MG',NULL,'PSB','DEFERIDO',NULL),
  -- Registro indeferido: segue visível, com o status exibido.
  ('a1000000-0000-4000-8000-000000000003','tse-3-2026',2026,'SENADOR','FEDERAL','SP',NULL,'PL','INDEFERIDO',NULL),
  -- Mandato anterior a 2001: a fonte não publica voto individual do período.
  ('a1000000-0000-4000-8000-000000000003','tse-3-1998',1998,'DEPUTADO_FEDERAL','FEDERAL','SP',NULL,'PL','DEFERIDO',true),
  ('a1000000-0000-4000-8000-000000000003','tse-3-1996',1996,'VEREADOR','MUNICIPAL','SP','São Paulo','PL','DEFERIDO',true),
  ('a1000000-0000-4000-8000-000000000004','tse-4-2026',2026,'DEPUTADO_FEDERAL','FEDERAL','BA',NULL,'PSDB','DEFERIDO',NULL),
  ('a1000000-0000-4000-8000-000000000004','tse-4-2022',2022,'DEPUTADO_FEDERAL','FEDERAL','BA',NULL,'PSDB','DEFERIDO',true),
  ('a1000000-0000-4000-8000-000000000004','tse-4-2018',2018,'DEPUTADO_ESTADUAL','ESTADUAL','BA',NULL,'PSDB','DEFERIDO',true),
  ('a1000000-0000-4000-8000-000000000005','tse-5-2026',2026,'SENADOR','FEDERAL','AP',NULL,'MDB','DEFERIDO',NULL),
  ('a1000000-0000-4000-8000-000000000005','tse-5-2022',2022,'DEPUTADO_FEDERAL','FEDERAL','AP',NULL,'MDB','DEFERIDO',true),
  ('a1000000-0000-4000-8000-000000000006','tse-6-2026',2026,'GOVERNADOR','ESTADUAL','GO',NULL,'PP','DEFERIDO',NULL);

INSERT INTO identificador_externo (politico_id, sistema, identificador, metodo_resolucao) VALUES
  ('a1000000-0000-4000-8000-000000000001','CAMARA','204528','DETERMINISTICO'),
  ('a1000000-0000-4000-8000-000000000003','CAMARA','221328','DETERMINISTICO'),
  ('a1000000-0000-4000-8000-000000000004','CAMARA','204560','DETERMINISTICO'),
  ('a1000000-0000-4000-8000-000000000005','CAMARA','204379','DETERMINISTICO');

-- Ementas reais da Câmara.
INSERT INTO proposicao
  (id, casa, id_externo, sigla_tipo, numero, ano, ementa, data_apresentacao, situacao_atual, url_inteiro_teor, url_tramitacao) VALUES
  (1197773,'CAMARA','1197773','PL',4015,2023,
   'Altera o art. 121 do Decreto-Lei nº 2.848, de 7 de dezembro de 1940 - Código Penal - para prever como homicídio qualificado o crime cometido contra agente de saúde em serviço.',
   '2023-08-14','Aguardando Parecer do Relator',
   'https://www.camara.leg.br/proposicoesWeb/prop_mostrarintegra?codteor=1',
   'https://www.camara.leg.br/proposicoesWeb/fichadetramitacao?idProposicao=1197773'),
  (618609,'CAMARA','618609','PL',6155,2023,
   'Institui o dia 25 de julho como o "Dia Nacional da Cultura e da Paz", e dá outras providências.',
   '2023-11-30','Arquivada', NULL,
   'https://www.camara.leg.br/proposicoesWeb/fichadetramitacao?idProposicao=618609'),
  (900001,'ALESP','900001','PL',512,2019,
   'Dispõe sobre a obrigatoriedade de divulgação, em sítio eletrônico, das obras públicas estaduais em andamento no Estado de São Paulo.',
   '2019-05-22','Aprovada em Comissão', NULL,
   'https://www.al.sp.gov.br/propositura/?id=900001'),
  (369205,'CAMARA','369205','PL',4089,1999,
   'Torna obrigatória a homologação em cartório de todo contrato de empréstimo consignado a ser efetuado por aposentado ou pensionista.',
   '1999-03-10','Arquivada', NULL,
   'https://www.camara.leg.br/proposicoesWeb/fichadetramitacao?idProposicao=369205'),
  (2074843,'CAMARA','2074843','PL',6064,2023,
   'Dispõe sobre direito a dano moral e concessão de pensão especial à pessoa com Microcefalia ou com Síndrome Congênita decorrente do Zika vírus.',
   '2023-11-21','Pronta para Pauta', NULL,
   'https://www.camara.leg.br/proposicoesWeb/fichadetramitacao?idProposicao=2074843');

SELECT setval(pg_get_serial_sequence('proposicao','id'), (SELECT max(id) FROM proposicao));

INSERT INTO proposicao_tema (proposicao_id, tema) VALUES
  (1197773,'Direito Penal e Processual Penal'), (1197773,'Saúde'),
  (618609,'Cultura'),
  (900001,'Administração Pública'), (900001,'Transparência'),
  (369205,'Direito Civil e Processual Civil'), (369205,'Previdência'),
  (2074843,'Saúde'), (2074843,'Direitos Humanos');

-- Coautores fora da coorte entram como NOME, sem politico_id: a autoria da
-- matéria fica completa sem manter registro pessoal de quem não é candidato.
INSERT INTO proposicao_autor (proposicao_id, politico_id, autor_nome, autor_principal) VALUES
  (1197773,'a1000000-0000-4000-8000-000000000001','Adriana Ventura',true),
  (1197773,'a1000000-0000-4000-8000-000000000004','Adolfo Viana',false),
  (1197773, NULL,'Reginaldo Tavares de Almeida',false),
  (1197773, NULL,'Marta Figueiró Bastos',false),
  (618609,'a1000000-0000-4000-8000-000000000001','Adriana Ventura',true),
  (900001,'a1000000-0000-4000-8000-000000000001','Adriana Ventura',true),
  (900001, NULL,'Carlos Eduardo Pignatari',false),
  (369205,'a1000000-0000-4000-8000-000000000003','Adilson Barroso',true),
  (2074843,'a1000000-0000-4000-8000-000000000004','Adolfo Viana',true),
  (2074843, NULL,'Helena Mourão de Lima',false);

INSERT INTO votacao
  (id, casa, id_externo, proposicao_id, data_votacao, descricao, tipo, ambito, aprovada, url_fonte) VALUES
  (555111,'CAMARA','555111',1197773,'2023-06-15T17:32:00Z','Aprovação do requerimento de urgência para o PL 4015/2023','NOMINAL','PLENARIO',true,'https://www.camara.leg.br/votacoes/555111'),
  (555113,'CAMARA','555113',2074843,'2023-06-18T19:40:00Z','Votação em turno único do PL 6064/2023','NOMINAL','PLENARIO',false,'https://www.camara.leg.br/votacoes/555113'),
  -- Simbólica: nenhum voto_nominal associado, de propósito.
  (555112,'CAMARA','555112',618609,'2023-06-20T13:05:00Z','Redação final do PL 6155/2023','SIMBOLICA','PLENARIO',true,'https://www.camara.leg.br/votacoes/555112'),
  (777001,'ALESP','777001',900001,'2021-04-07T14:00:00Z','Parecer do relator na Comissão de Constituição, Justiça e Redação','NOMINAL','COMISSAO',true,'https://www.al.sp.gov.br/votacao/777001'),
  (555114,'CAMARA','555114',NULL,'2023-09-05T18:10:00Z','Emenda nº 3 ao PL 2234/2023','NOMINAL','PLENARIO',true,'https://www.camara.leg.br/votacoes/555114'),
  (556001,'CAMARA','556001',2074843,'2023-10-11T16:20:00Z','Votação em turno único do PL 6064/2023','NOMINAL','PLENARIO',false,'https://www.camara.leg.br/votacoes/556001');

-- As duas fatias mais recentes (W11 Senado, W12 Alesp) não tinham NENHUM caso
-- no ambiente de demonstração — quem abrisse o seed não veria voto secreto
-- nem voto em separado em lugar nenhum. `secreta` só existe nesta segunda
-- lista porque é a única com valor diferente do default (false).
INSERT INTO votacao
  (id, casa, id_externo, proposicao_id, data_votacao, descricao, tipo, ambito,
   secreta, aprovada, url_fonte) VALUES
  (888001,'SENADO','888001',NULL,'2024-05-14T16:20:00Z',
   'Votação secreta do requerimento de urgência','NOMINAL','PLENARIO',
   true,true,'https://legis.senado.leg.br/dadosabertos/votacao?codigoSessao=888001'),
  (777002,'ALESP','777002',900001,'2021-04-14T14:30:00Z',
   'Nova deliberação na Comissão de Constituição, Justiça e Redação','NOMINAL','COMISSAO',
   false,NULL,'https://www.al.sp.gov.br/votacao/777002');

SELECT setval(pg_get_serial_sequence('votacao','id'), (SELECT max(id) FROM votacao));

-- A ALESP é a única fonte com voto_origem_codigo: ela publica o código
-- <TipoVoto> ('F') separado do texto <Voto> ("Favorável ao parecer"), e é o
-- código que resolve em mapeamento_voto. Câmara e Senado publicam um campo só.
INSERT INTO voto_nominal (votacao_id, politico_id, voto, voto_origem,
                          voto_origem_codigo) VALUES
  (555111,'a1000000-0000-4000-8000-000000000001','SIM','Sim',NULL),
  (555113,'a1000000-0000-4000-8000-000000000001','OBSTRUCAO','Obstrução',NULL),
  (777001,'a1000000-0000-4000-8000-000000000001','SIM','Favorável ao parecer','F'),
  (556001,'a1000000-0000-4000-8000-000000000004','NAO','Não',NULL);

-- AUSENTE é sempre DERIVADO na Câmara — a fonte nunca publica "faltou"
-- (achado B8). Uma linha FONTE aqui contradiria a própria regra que o
-- indicador "apurado pela plataforma" existe para exibir, e destoaria do
-- fixture espelhado em web/src/lib/api/fixtures.ts, que já está correto.
INSERT INTO voto_nominal (votacao_id, politico_id, voto, voto_origem,
                          origem_registro) VALUES
  (555114,'a1000000-0000-4000-8000-000000000001','AUSENTE',NULL,'DERIVADO');

-- SECRETO: o Senado registra QUE a pessoa participou, não COMO ela votou —
-- não é recusa a votar, é o próprio regimento (achado W11/A12). Nada aqui é
-- DERIVADO: a Casa publica a bancada inteira, então mesmo o "participou" é
-- FONTE.
INSERT INTO voto_nominal (votacao_id, politico_id, voto, voto_origem) VALUES
  (888001,'a1000000-0000-4000-8000-000000000001','SECRETO','Votou');

-- VOTO_EM_SEPARADO: votou apresentando parecer escrito divergente do
-- relator. A Alesp não diz se o divergente é favorável ou contrário ao
-- projeto (achado W12) — é o oposto de abstenção, não uma variação dela.
-- voto_origem_codigo='S' é o código real da fonte; o texto é o que a UI
-- mostra em "registrado como".
INSERT INTO voto_nominal (votacao_id, politico_id, voto, voto_origem,
                          voto_origem_codigo) VALUES
  (777002,'a1000000-0000-4000-8000-000000000001','VOTO_EM_SEPARADO',
   'Com o Voto em Separado','S');

-- Votos de outros parlamentares, para o placar agregado ter massa realista.
DO $$
DECLARE i INT;
BEGIN
  FOR i IN 1..40 LOOP
    INSERT INTO politico (id, nome_civil)
    VALUES (gen_random_uuid(), 'Parlamentar de Exemplo ' || i);
  END LOOP;
END $$;

-- Distribui os votos de exemplo entre as posições, sem tocar nos perfis reais.
-- SEM 'AUSENTE' no rodízio: a Câmara nunca publica ausência como FONTE
-- (achado B8), e este INSERT não tem como marcar origem_registro='DERIVADO'
-- por linha — o caso DERIVADO já está coberto pela linha 555114, acima.
INSERT INTO voto_nominal (votacao_id, politico_id, voto, voto_origem)
SELECT 555111, p.id,
       (ARRAY['SIM','SIM','SIM','NAO','ABSTENCAO'])[1 + (row_number() OVER (ORDER BY p.id)) % 5]::tipo_voto_enum,
       (ARRAY['Sim','Sim','Sim','Não','Abstenção'])[1 + (row_number() OVER (ORDER BY p.id)) % 5]
  FROM politico p
 WHERE p.nome_civil LIKE 'Parlamentar de Exemplo %';

-- Execuções de ingestão: a Alesp com FALHA de propósito, para a UI ter que
-- avisar que a sincronização seguinte falhou em vez de sugerir dado em dia.
INSERT INTO ingestao_execucao (fonte, tipo_job, status, iniciado_em, concluido_em, watermark_novo) VALUES
  ('CAMARA','INCREMENTAL','CONCLUIDA','2026-08-18T04:10:00Z','2026-08-18T04:12:33Z','2026-08-18T04:12:33Z'),
  ('SENADO','INCREMENTAL','CONCLUIDA','2026-08-18T04:13:00Z','2026-08-18T04:15:02Z','2026-08-18T04:15:02Z'),
  ('TSE','COORTE','CONCLUIDA','2026-08-17T02:00:00Z','2026-08-17T02:00:11Z','2026-08-17T02:00:11Z'),
  ('ALESP','INCREMENTAL','CONCLUIDA','2026-08-15T03:38:00Z','2026-08-15T03:40:19Z','2026-08-15T03:40:19Z'),
  ('ALESP','INCREMENTAL','FALHA','2026-08-18T03:38:00Z',NULL,NULL);

-- ---------------------------------------------------------------------------
-- Finalização: em produção quem chama é o FinalizadorDeIngestao, ao fim de
-- cada ingestão — marca quem tem atuação legislativa, DEPOIS reconstrói a
-- projeção (perfil_leitura copia a coluna; invertido publicaria o valor
-- velho). Aqui o seed faz o mesmo papel, na mesma ordem, e é por isso que
-- possui_atuacao_legislativa não é semeada acima: ela é CALCULADA daqui.
-- ---------------------------------------------------------------------------
SELECT marcar_atuacao_legislativa();
SELECT reconstruir_perfil_leitura();
