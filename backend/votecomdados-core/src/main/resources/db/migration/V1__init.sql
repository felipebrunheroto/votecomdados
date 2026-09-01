-- ============================================================================
-- VoteComDados — Schema PostgreSQL
--
-- ESCOPO DO MVP: pessoas candidatas na eleição de 2026 (qualquer cargo) e,
-- para elas:
--   * trajetória eleitoral nos três níveis (vereador, prefeito, deputado
--     estadual, federal...), via TSE — fonte única, uniforme e barata;
--   * atuação legislativa FEDERAL completa, via Câmara e Senado;
--   * atuação legislativa ESTADUAL apenas em SÃO PAULO, via Alesp.
--
-- FORA DO MVP (próxima versão): atuação legislativa das demais 26 assembleias
-- estaduais e das câmaras municipais. Note que a trajetória ELEITORAL
-- municipal continua incluída — vem dos mesmos arquivos do TSE, sem custo
-- adicional, e sem ela a linha do tempo do candidato ficaria com buracos.
--
-- Quem não é candidato em 2026 não tem registro pessoal aqui.
--
-- A cobertura é ASSIMÉTRICA, e `cobertura_fonte` distingue três situações que
-- geram mensagens diferentes ao eleitor: cobrimos / a fonte não publica /
-- ainda não integramos. Ver docs/ARQUITETURA.md § 5.
--
-- Schema curado (public): Politico, IdentificadorExterno, Candidatura,
--            Proposicao, ProposicaoTema, ProposicaoAutor, Votacao, VotoNominal
-- Referência: MapeamentoVoto (origem -> enum), CoberturaFonte (limites da fonte)
-- Controle de ingestão: IngestaoExecucao (watermark auditável)
-- Schema bruto (staging): PayloadBruto (JSONB redigido), RegistroRejeitado
--
-- Requer PostgreSQL 15+ (usa UNIQUE ... NULLS NOT DISTINCT).
--
-- Ver docs/ARQUITETURA.md para o papel de cada bloco no pipeline e
-- docs/REVISAO_ARQUITETURA.md para as decisões que moldaram este schema.
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid(), hmac()
CREATE EXTENSION IF NOT EXISTS pg_trgm;    -- busca fuzzy / ILIKE acelerada
CREATE EXTENSION IF NOT EXISTS unaccent;   -- normalização de acentuação

-- ----------------------------------------------------------------------------
-- unaccent_imutavel: wrapper obrigatório para uso em índice/coluna gerada
-- ----------------------------------------------------------------------------
-- unaccent() é declarada STABLE, não IMMUTABLE, porque resolve o dicionário
-- de busca pelo search_path em tempo de execução. Postgres rejeita função
-- não-imutável em coluna GENERATED e em índice de expressão, então usar
-- unaccent() direto nesses lugares falha na criação do schema.
-- Fixar o dicionário explicitamente torna a chamada determinística e permite
-- declará-la IMMUTABLE.

CREATE FUNCTION unaccent_imutavel(texto TEXT)
RETURNS TEXT
LANGUAGE sql
IMMUTABLE STRICT PARALLEL SAFE
AS $$ SELECT public.unaccent('public.unaccent'::regdictionary, texto) $$;

-- ----------------------------------------------------------------------------
-- Tipos enumerados
-- ----------------------------------------------------------------------------

-- Cobre os cargos disputados em 2026 e também os das eleições municipais, que
-- entram pela trajetória eleitoral: a plataforma mostra toda a vida política
-- da pessoa (vereador, prefeito, deputado estadual...), não só o nível federal.
CREATE TYPE cargo_enum AS ENUM (
    'PRESIDENTE', 'VICE_PRESIDENTE',
    'GOVERNADOR', 'VICE_GOVERNADOR',
    'SENADOR', 'PRIMEIRO_SUPLENTE', 'SEGUNDO_SUPLENTE',
    'DEPUTADO_FEDERAL', 'DEPUTADO_ESTADUAL', 'DEPUTADO_DISTRITAL',
    'PREFEITO', 'VICE_PREFEITO', 'VEREADOR'
);

CREATE TYPE esfera_enum AS ENUM ('FEDERAL', 'ESTADUAL', 'MUNICIPAL');

-- ALESP entra como piloto de conector estadual. Escalar para as outras 26
-- assembleias justificaria trocar este enum por tabela de referência — não
-- vale a complexidade para uma fonte só.
CREATE TYPE casa_legislativa_enum AS ENUM ('CAMARA', 'SENADO', 'ALESP');

-- Voto de comissão NÃO é voto de plenário. Exibir os dois sem distinção
-- sugeriria que um parecer em comissão tem o mesmo peso de uma votação de
-- plenário — distorção que a plataforma não pode produzir.
CREATE TYPE ambito_votacao_enum AS ENUM ('PLENARIO', 'COMISSAO');
CREATE TYPE tipo_voto_enum AS ENUM ('SIM', 'NAO', 'ABSTENCAO', 'AUSENTE', 'OBSTRUCAO', 'ART_17');
CREATE TYPE tipo_votacao_enum AS ENUM ('NOMINAL', 'SIMBOLICA');
CREATE TYPE status_candidatura_enum AS ENUM ('DEFERIDO', 'INDEFERIDO', 'CASSADO', 'RENUNCIA', 'APTO', 'INAPTO');
CREATE TYPE metodo_resolucao_enum AS ENUM ('DETERMINISTICO', 'FUZZY');
CREATE TYPE fonte_enum AS ENUM ('CAMARA', 'SENADO', 'TSE', 'ALESP');
CREATE TYPE tipo_job_enum AS ENUM ('COORTE', 'BACKFILL', 'INCREMENTAL');
CREATE TYPE status_execucao_enum AS ENUM ('EM_ANDAMENTO', 'CONCLUIDA', 'FALHA');
-- Três situações distintas, com mensagens distintas para o eleitor:
--   DISPONIVEL              -> cobrimos, desde a data informada
--   NAO_PUBLICADO_PELA_FONTE-> a Casa não publica; nenhuma engenharia resolve
--   FORA_DO_ESCOPO_MVP      -> pode existir, mas ainda não integramos
-- Confundir os dois últimos seria desonesto: "não existe" e "não fizemos"
-- são coisas diferentes para quem consulta.
CREATE TYPE status_cobertura_enum AS ENUM (
    'DISPONIVEL', 'NAO_PUBLICADO_PELA_FONTE', 'FORA_DO_ESCOPO_MVP'
);

CREATE TYPE motivo_rejeicao_enum AS ENUM (
    'POLITICO_NAO_RESOLVIDO',   -- voto/autoria de parlamentar ainda não vinculado
    'VOTACAO_DESCONHECIDA',
    'PROPOSICAO_DESCONHECIDA',
    'VALOR_VOTO_NAO_MAPEADO',   -- string de voto ausente em mapeamento_voto
    'PAYLOAD_INVALIDO'
);

-- ----------------------------------------------------------------------------
-- Politico: pessoa candidata em 2026 — a coorte É o escopo do sistema
-- ----------------------------------------------------------------------------
-- INVARIANTE CENTRAL: só existe linha em `politico` para quem tem registro de
-- candidatura em 2026. Não é uma base de parlamentares históricos filtrada na
-- exibição — quem não é candidato simplesmente não tem registro pessoal aqui
-- (coautores fora da coorte entram como nome em proposicao_autor, sem perfil).
--
-- A coorte define o que ingerir, e não o contrário: o job COORTE sincroniza a
-- lista do TSE, o BACKFILL busca o histórico completo apenas dessas pessoas, e
-- quem sai da lista é removido por poda (ON DELETE CASCADE limpa o histórico).
--
-- Consequência de LGPD: o tratamento se limita a quem se apresenta ao
-- eleitorado, o que é uma posição de minimização muito mais defensável do que
-- manter um arquivo permanente de todos os parlamentares.

CREATE TABLE politico (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    nome_civil        TEXT NOT NULL,
    -- Nulável de propósito: "nome de urna" é conceito do domínio eleitoral
    -- (TSE). Um parlamentar conhecido apenas pelo cadastro institucional da
    -- Câmara/Senado — suplente que assumiu, ou eleito antes da janela de
    -- candidaturas carregada — não tem esse campo. Exigi-lo tornava
    -- impossível criar o registro que a ingestão de votos precisa primeiro,
    -- e o resultado era voto descartado por violação de FK.
    nome_urna         TEXT,
    nome_parlamentar  TEXT,   -- como a Casa o identifica em votações
    -- NÃO é um hash simples: o espaço de CPFs válidos (~10^10) é enumerável
    -- por força bruta em segundos, então SHA-256 puro seria equivalente a
    -- guardar o CPF. Aqui vai HMAC-SHA256 com pepper guardado no gerenciador
    -- de segredos — sem a chave, a enumeração offline é inviável.
    -- A coluna é opcional e descartável: após a resolução de identidade, o
    -- vínculo vive em identificador_externo e este campo pode ser zerado.
    cpf_hmac          CHAR(64),
    data_nascimento   DATE,
    genero            TEXT,
    foto_url          TEXT,
    -- Mantido pelo pipeline ao fim do backfill: true se a pessoa tem QUALQUER
    -- atuação legislativa registrada (federal via Câmara/Senado, ou estadual
    -- via Alesp). A grande maioria dos ~28 mil candidatos não tem nenhuma, e
    -- esta flag separa quem tem algo a exibir (pré-renderização estática) de
    -- quem só precisa da resposta "sem mandato legislativo anterior".
    possui_atuacao_legislativa BOOLEAN NOT NULL DEFAULT false,
    criado_em         TIMESTAMPTZ NOT NULL DEFAULT now(),
    atualizado_em     TIMESTAMPTZ NOT NULL DEFAULT now(),
    nome_busca        TSVECTOR GENERATED ALWAYS AS (
        to_tsvector('portuguese',
            unaccent_imutavel(coalesce(nome_civil, '') || ' ' ||
                     coalesce(nome_urna, '') || ' ' ||
                     coalesce(nome_parlamentar, '')))
    ) STORED
);

CREATE UNIQUE INDEX idx_politico_cpf_hmac ON politico (cpf_hmac) WHERE cpf_hmac IS NOT NULL;
CREATE INDEX idx_politico_nome_civil_trgm ON politico USING gin (unaccent_imutavel(nome_civil) gin_trgm_ops);
CREATE INDEX idx_politico_nome_urna_trgm ON politico USING gin (unaccent_imutavel(nome_urna) gin_trgm_ops);
CREATE INDEX idx_politico_busca ON politico USING gin (nome_busca);
-- Lista para pré-renderização estática: só quem tem atuação a mostrar.
CREATE INDEX idx_politico_com_atuacao ON politico (id) WHERE possui_atuacao_legislativa;

-- ----------------------------------------------------------------------------
-- IdentificadorExterno: mapeamento N:1 para os IDs de cada sistema de origem
-- ----------------------------------------------------------------------------

CREATE TABLE identificador_externo (
    id                    BIGSERIAL PRIMARY KEY,
    politico_id           UUID NOT NULL REFERENCES politico(id) ON DELETE CASCADE,
    sistema               fonte_enum NOT NULL,
    identificador         TEXT NOT NULL,
    metodo_resolucao      metodo_resolucao_enum NOT NULL DEFAULT 'DETERMINISTICO',
    -- Preenchido apenas quando o casamento veio do pipeline fuzzy (nome + UF + partido).
    -- Serve de trilha de auditoria: qualquer match abaixo do threshold fica
    -- marcado para revisão humana antes de entrar em produção.
    score_confianca       NUMERIC(5, 4),
    revisado_manualmente  BOOLEAN NOT NULL DEFAULT false,
    criado_em             TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (sistema, identificador)
);

CREATE INDEX idx_identext_politico ON identificador_externo (politico_id);

-- ----------------------------------------------------------------------------
-- Candidatura: um registro por politico x ano_eleicao x turno
-- ----------------------------------------------------------------------------
-- A candidatura de 2026 é o que define a coorte: todo `politico` tem ao menos
-- uma linha aqui com ano_eleicao = 2026. As candidaturas anteriores da mesma
-- pessoa são carregadas como contexto histórico (e ajudam a resolução de
-- identidade a casar mandatos antigos).
--
-- Status: entram TODOS os registros, inclusive indeferidos e sub judice — o
-- status é exibido ao eleitor. Omitir quem está em disputa judicial faria a
-- plataforma parecer estar escondendo um candidato, e o próprio andamento do
-- registro é informação pública de interesse.

CREATE TABLE candidatura (
    id                BIGSERIAL PRIMARY KEY,
    politico_id       UUID NOT NULL REFERENCES politico(id) ON DELETE CASCADE,
    sq_candidato_tse  TEXT NOT NULL,
    ano_eleicao       SMALLINT NOT NULL,
    turno             SMALLINT NOT NULL DEFAULT 1,
    cargo             cargo_enum NOT NULL,
    -- Derivável do cargo, mas explícito porque é o eixo de agrupamento da
    -- trajetória na UI e o filtro mais usado nas consultas.
    esfera            esfera_enum NOT NULL,
    uf                CHAR(2) NOT NULL,   -- 'BR' em cargos nacionais
    -- Preenchidos apenas em eleições municipais (vereador, prefeito).
    municipio            TEXT,
    codigo_municipio_tse TEXT,
    partido_sigla     TEXT NOT NULL,
    partido_numero    SMALLINT,
    numero_urna       INTEGER,
    status            status_candidatura_enum NOT NULL,
    votos_recebidos   INTEGER,
    eleito            BOOLEAN,
    criado_em         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (sq_candidato_tse, ano_eleicao)
);

CREATE INDEX idx_candidatura_politico ON candidatura (politico_id);
CREATE INDEX idx_candidatura_uf_cargo ON candidatura (uf, cargo, ano_eleicao);
-- Busca e filtro da eleição corrente, que é a tela principal do produto.
CREATE INDEX idx_candidatura_2026 ON candidatura (cargo, uf) WHERE ano_eleicao = 2026;
-- Trajetória: todas as disputas da pessoa, da mais recente para a mais antiga.
CREATE INDEX idx_candidatura_trajetoria ON candidatura (politico_id, ano_eleicao DESC);

-- ----------------------------------------------------------------------------
-- Proposicao: matérias legislativas (PL, PEC, MPV, ...) de Câmara ou Senado
-- ----------------------------------------------------------------------------

CREATE TABLE proposicao (
    id                  BIGSERIAL PRIMARY KEY,
    casa                casa_legislativa_enum NOT NULL,
    id_externo          TEXT NOT NULL,  -- id numérico (Câmara) ou código da matéria (Senado)
    sigla_tipo          TEXT NOT NULL,  -- PL, PEC, MPV, PLP...
    numero              INTEGER,
    ano                 SMALLINT NOT NULL,
    ementa              TEXT NOT NULL,
    data_apresentacao   DATE,
    situacao_atual      TEXT,
    url_inteiro_teor    TEXT,
    url_tramitacao      TEXT NOT NULL,  -- fonte oficial, sempre exibida na UI
    atualizado_em       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (casa, id_externo)
);

CREATE INDEX idx_proposicao_ementa_trgm ON proposicao USING gin (unaccent_imutavel(ementa) gin_trgm_ops);

-- ----------------------------------------------------------------------------
-- ProposicaoTema: relação N:N — uma matéria tem vários temas na origem
-- ----------------------------------------------------------------------------
-- Antes era uma coluna `tema TEXT` em proposicao, o que era duplamente
-- errado: a fonte (arquivo proposicoesTemas da Câmara) associa múltiplos
-- temas por matéria, e nenhum passo do pipeline chegava a popular o campo.

CREATE TABLE proposicao_tema (
    proposicao_id  BIGINT NOT NULL REFERENCES proposicao(id) ON DELETE CASCADE,
    tema           TEXT NOT NULL,
    PRIMARY KEY (proposicao_id, tema)
);

CREATE INDEX idx_proposicaotema_tema ON proposicao_tema (tema);

-- ----------------------------------------------------------------------------
-- ProposicaoAutor: relação N:N entre proposição e autores
-- ----------------------------------------------------------------------------

-- Uma matéria costuma ter vários autores, e a maioria não é candidata em 2026.
-- `autor_nome` é SEMPRE preenchido com o nome como consta na fonte;
-- `politico_id` só é preenchido quando o autor pertence à coorte.
--
-- Assim a lista de autoria exibida continua factualmente completa (omitir
-- coautores distorceria o registro da matéria, o mesmo tipo de erro do B5),
-- sem manter registro pessoal de quem não é candidato: um nome numa lista de
-- autoria não é um dossiê — não tem perfil, histórico, foto nem página.

CREATE TABLE proposicao_autor (
    id               BIGSERIAL PRIMARY KEY,
    proposicao_id    BIGINT NOT NULL REFERENCES proposicao(id) ON DELETE CASCADE,
    politico_id      UUID REFERENCES politico(id) ON DELETE CASCADE,
    autor_nome       TEXT NOT NULL,
    autor_principal  BOOLEAN NOT NULL DEFAULT true,
    UNIQUE (proposicao_id, autor_nome)
);

CREATE INDEX idx_propautor_politico ON proposicao_autor (politico_id)
    WHERE politico_id IS NOT NULL;

-- ----------------------------------------------------------------------------
-- Votacao: um evento de votação em plenário (nominal ou simbólica)
-- ----------------------------------------------------------------------------

CREATE TABLE votacao (
    id               BIGSERIAL PRIMARY KEY,
    casa             casa_legislativa_enum NOT NULL,
    id_externo       TEXT NOT NULL,
    proposicao_id    BIGINT REFERENCES proposicao(id),
    data_votacao     TIMESTAMPTZ NOT NULL,
    descricao        TEXT NOT NULL,
    tipo             tipo_votacao_enum NOT NULL,
    -- Plenário e comissão têm pesos políticos diferentes; a ALESP só publica
    -- comissão. Misturá-los numa lista única faria um parecer de comissão
    -- parecer uma deliberação de plenário.
    ambito           ambito_votacao_enum NOT NULL DEFAULT 'PLENARIO',
    -- Quando tipo = 'SIMBOLICA' não há voto_nominal associado; a UI deve
    -- deixar isso explícito em vez de mostrar uma lista vazia sem contexto.
    aprovada         BOOLEAN,
    placar_sim       INTEGER,
    placar_nao       INTEGER,
    placar_abstencao INTEGER,
    url_fonte        TEXT NOT NULL,
    UNIQUE (casa, id_externo)
);

CREATE INDEX idx_votacao_proposicao ON votacao (proposicao_id);
CREATE INDEX idx_votacao_data ON votacao (data_votacao);

-- ----------------------------------------------------------------------------
-- MapeamentoVoto: tradução origem -> enum, versionada como DADO
-- ----------------------------------------------------------------------------
-- Colapsar os rótulos das Casas em um enum é decisão editorial, não detalhe
-- técnico: "Obstrução" é orientação regimental de bancada, "Art. 17" é o
-- presidente que não vota, e "ausente" pode ser licença médica, missão
-- oficial ou falta. Mantendo o mapeamento como tabela (e não como switch em
-- código), ele fica revisável por quem entende de processo legislativo e
-- auditável em produção.

CREATE TABLE mapeamento_voto (
    id             BIGSERIAL PRIMARY KEY,
    fonte          fonte_enum NOT NULL,
    valor_origem   TEXT NOT NULL,
    voto           tipo_voto_enum NOT NULL,
    observacao     TEXT,   -- nota de metodologia exibível ao usuário
    vigente_desde  DATE NOT NULL DEFAULT CURRENT_DATE,
    UNIQUE (fonte, valor_origem)
);

-- A necessidade desta tabela ficou evidente ao inspecionar a ALESP: são
-- **455 rótulos distintos** em 210 mil votos de comissão, texto livre, com
-- erros de digitação ("proejto") e frases inteiras. Um `switch` em código
-- seria insustentável.
--
-- ~97% começam com "Favorável" e ~2% com "Contrário", mas a cauda de ~1%
-- inclui casos que NÃO têm tradução honesta para o enum — "Com o Voto em
-- Separado" (1.827 ocorrências) é divergência regimental, não sim nem não.
-- Esses vão para quarentena (VALOR_VOTO_NAO_MAPEADO) em vez de serem
-- forçados a uma categoria: preferimos não classificar a classificar errado.

INSERT INTO mapeamento_voto (fonte, valor_origem, voto, observacao) VALUES
    ('CAMARA', 'Sim',         'SIM',       NULL),
    ('CAMARA', 'Não',         'NAO',       NULL),
    ('CAMARA', 'Abstenção',   'ABSTENCAO', NULL),
    ('CAMARA', 'Obstrução',   'OBSTRUCAO', 'Obstrução é manobra regimental de orientação de bancada, não um voto contrário ao mérito.'),
    ('CAMARA', 'Artigo 17',   'ART_17',    'O presidente da Casa só vota em situações previstas no regimento.'),
    ('ALESP',  'Favorável ao parecer',          'SIM', 'Voto em comissão, favorável ao parecer do relator — não é votação de plenário.'),
    ('ALESP',  'Favorável ao voto do relator',  'SIM', 'Voto em comissão, favorável ao voto do relator — não é votação de plenário.'),
    ('ALESP',  'Favorável ao projeto',          'SIM', 'Voto em comissão — não é votação de plenário.'),
    ('ALESP',  'Contrário ao parecer',          'NAO', 'Voto em comissão — não é votação de plenário.'),
    ('ALESP',  'Contrário ao voto do relator',  'NAO', 'Voto em comissão — não é votação de plenário.'),
    ('ALESP',  'Contrário ao projeto',          'NAO', 'Voto em comissão — não é votação de plenário.');
-- Demais rótulos: acrescentar aqui após revisão de quem entende de processo
-- legislativo. O que não estiver mapeado vai para quarentena, visível.

-- ----------------------------------------------------------------------------
-- VotoNominal: o voto individual de cada parlamentar em uma votação nominal
-- ----------------------------------------------------------------------------

CREATE TABLE voto_nominal (
    id           BIGSERIAL PRIMARY KEY,
    votacao_id   BIGINT NOT NULL REFERENCES votacao(id) ON DELETE CASCADE,
    politico_id  UUID NOT NULL REFERENCES politico(id) ON DELETE CASCADE,
    voto         tipo_voto_enum NOT NULL,
    -- String literal da fonte, preservada sempre. O enum acima é uma
    -- interpretação nossa; este campo é o fato. Sem ele, um erro de
    -- mapeamento descoberto meses depois seria irrecuperável a partir do
    -- dado curado, e a UI não poderia mostrar o rótulo oficial ao lado do
    -- normalizado.
    voto_origem  TEXT NOT NULL,
    UNIQUE (votacao_id, politico_id)
);

CREATE INDEX idx_votonominal_politico ON voto_nominal (politico_id);
CREATE INDEX idx_votonominal_votacao ON voto_nominal (votacao_id);

-- ----------------------------------------------------------------------------
-- CoberturaFonte: até onde cada fonte realmente vai
-- ----------------------------------------------------------------------------
-- "Todo o histórico" é limitado pelo que a fonte publica, e o limite não é
-- uniforme: a Câmara tem proposições desde 1934, mas votos nominais
-- individuais só a partir de 2001. Um candidato que foi deputado nos anos 90
-- terá autoria e NUNCA terá votações — não por falha nossa, e a UI precisa
-- dizer isso em vez de mostrar uma aba vazia ambígua (mesma regra da votação
-- simbólica).
--
-- Modelado como dado, e não como constante no código, para que a API possa
-- expor a limitação e a UI renderizá-la sem hardcode.

CREATE TABLE cobertura_fonte (
    id                BIGSERIAL PRIMARY KEY,
    esfera            esfera_enum NOT NULL,
    -- NULL = regra vale para todas as UFs. Uma linha com UF específica tem
    -- precedência sobre a genérica: hoje ESTADUAL/SP é coberto pela Alesp
    -- enquanto ESTADUAL/demais está fora do escopo do MVP.
    uf                CHAR(2),
    recurso           TEXT NOT NULL,
    status            status_cobertura_enum NOT NULL,
    fonte             fonte_enum,   -- NULL quando não há fonte integrada
    disponivel_desde  DATE,
    observacao        TEXT NOT NULL,
    -- Impede a combinação incoerente que causaria mensagem errada na UI:
    -- só há data de início quando de fato cobrimos o recurso.
    CONSTRAINT cobertura_coerente CHECK (
        (status = 'DISPONIVEL'  AND fonte IS NOT NULL AND disponivel_desde IS NOT NULL)
     OR (status <> 'DISPONIVEL' AND disponivel_desde IS NULL)
    ),
    UNIQUE NULLS NOT DISTINCT (esfera, uf, recurso)
);

INSERT INTO cobertura_fonte (esfera, uf, recurso, status, fonte, disponivel_desde, observacao) VALUES
    -- Trajetória eleitoral: TSE cobre os três níveis de forma uniforme.
    ('FEDERAL',   NULL, 'candidatura',      'DISPONIVEL', 'TSE',    '1994-01-01', 'Candidaturas federais em dados abertos do TSE.'),
    ('ESTADUAL',  NULL, 'candidatura',      'DISPONIVEL', 'TSE',    '1994-01-01', 'Candidaturas estaduais em dados abertos do TSE.'),
    ('MUNICIPAL', NULL, 'candidatura',      'DISPONIVEL', 'TSE',    '1996-01-01', 'Candidaturas municipais (vereador, prefeito) em dados abertos do TSE.'),

    -- Atuação legislativa federal: escopo completo do MVP.
    ('FEDERAL',   NULL, 'proposicao',       'DISPONIVEL', 'CAMARA', '1934-01-01', 'Arquivos anuais de proposições disponíveis desde 1934.'),
    ('FEDERAL',   NULL, 'votacao_plenario', 'DISPONIVEL', 'CAMARA', '1990-01-01', 'Metadados e placar agregado das votações de plenário.'),
    ('FEDERAL',   NULL, 'voto_nominal',     'DISPONIVEL', 'CAMARA', '2001-01-01', 'Votos nominais individuais de plenário só existem a partir de 2001; mandatos anteriores não têm registro individual publicado.'),

    -- Atuação legislativa estadual em SP: piloto do MVP.
    ('ESTADUAL',  'SP', 'proposicao',       'DISPONIVEL', 'ALESP',  '1995-01-01', 'Proposituras e autoria no portal de dados abertos da Alesp.'),
    ('ESTADUAL',  'SP', 'votacao_comissao', 'DISPONIVEL', 'ALESP',  '1995-01-01', 'Votos individuais em comissões permanentes da Alesp.'),
    ('ESTADUAL',  'SP', 'voto_nominal',     'NAO_PUBLICADO_PELA_FONTE', 'ALESP', NULL, 'A Alesp não publica os votos nominais de plenário em dados abertos. A ausência é da fonte, não da plataforma.'),

    -- Demais estados: fora do MVP (a linha com UF NULL é o fallback).
    ('ESTADUAL',  NULL, 'proposicao',       'FORA_DO_ESCOPO_MVP', NULL, NULL, 'Nesta versão só a Assembleia de São Paulo está integrada. As demais assembleias ainda não foram cobertas.'),
    ('ESTADUAL',  NULL, 'voto_nominal',     'FORA_DO_ESCOPO_MVP', NULL, NULL, 'Nesta versão só a Assembleia de São Paulo está integrada. As demais assembleias ainda não foram cobertas.'),

    -- Municipal: fora do MVP, e sem fonte padronizada mesmo depois.
    ('MUNICIPAL', NULL, 'proposicao',       'FORA_DO_ESCOPO_MVP', NULL, NULL, 'A atuação em câmaras municipais não é coberta nesta versão. As 5.570 câmaras não publicam dados legislativos em formato padronizado.'),
    ('MUNICIPAL', NULL, 'voto_nominal',     'FORA_DO_ESCOPO_MVP', NULL, NULL, 'A atuação em câmaras municipais não é coberta nesta versão. As 5.570 câmaras não publicam dados legislativos em formato padronizado.');

-- Senado: preencher após o spike de integração (ver docs/REVISAO_ARQUITETURA.md, A12).

-- ----------------------------------------------------------------------------
-- IngestaoExecucao: controle e auditoria de cada execução do worker
-- ----------------------------------------------------------------------------

CREATE TABLE ingestao_execucao (
    id                     BIGSERIAL PRIMARY KEY,
    fonte                  fonte_enum NOT NULL,
    tipo_job               tipo_job_enum NOT NULL,
    parametros             JSONB,        -- ex.: {"ano": 2023} no backfill
    status                 status_execucao_enum NOT NULL DEFAULT 'EM_ANDAMENTO',
    iniciado_em            TIMESTAMPTZ NOT NULL DEFAULT now(),
    concluido_em           TIMESTAMPTZ,
    -- Marcador incremental: o job pergunta "o que mudou desde quando?".
    -- watermark_novo só passa a valer quando status = 'CONCLUIDA' — uma
    -- execução que falhou no meio não pode avançar o marcador, senão o
    -- ciclo seguinte pularia silenciosamente a janela não processada.
    -- Ao gravar, usar GREATEST(novo, atual): o marcador nunca retrocede.
    watermark_anterior     TIMESTAMPTZ,
    watermark_novo         TIMESTAMPTZ,
    registros_processados  INTEGER NOT NULL DEFAULT 0,
    registros_rejeitados   INTEGER NOT NULL DEFAULT 0,
    erro                   TEXT
);

-- Suporta a leitura crítica do job incremental (último watermark bem-sucedido
-- por fonte) e o endpoint GET /meta/status de frescor dos dados.
CREATE INDEX idx_execucao_watermark
    ON ingestao_execucao (fonte, concluido_em DESC)
    WHERE status = 'CONCLUIDA';

-- Exclusão mútua por fonte. Duas execuções simultâneas leriam o mesmo
-- watermark inicial e a última a terminar poderia gravar um marcador
-- ANTERIOR ao da outra — e a idempotência dos upserts não protege contra
-- isso: o dado não fica duplicado, fica faltando. O job também toma
-- pg_try_advisory_lock() antes de começar; este índice é a rede de segurança
-- no banco, para o caso de o lock ser liberado por morte do processo.
CREATE UNIQUE INDEX idx_execucao_uma_ativa_por_fonte
    ON ingestao_execucao (fonte)
    WHERE status = 'EM_ANDAMENTO';

-- Execuções órfãs (processo morto por OOM ou evicção nunca marca 'FALHA')
-- travariam a fonte para sempre por causa do índice acima. Um reaper no
-- início de cada job marca como 'FALHA' o que estiver 'EM_ANDAMENTO' há mais
-- do que o timeout máximo previsto para o job.

-- ----------------------------------------------------------------------------
-- staging.payload_bruto: payload de origem, redigido, antes de normalizar
-- ----------------------------------------------------------------------------

CREATE SCHEMA staging;

CREATE TABLE staging.payload_bruto (
    id                BIGSERIAL PRIMARY KEY,
    execucao_id       BIGINT NOT NULL REFERENCES ingestao_execucao(id),
    fonte             fonte_enum NOT NULL,
    recurso           TEXT NOT NULL,   -- 'proposicao', 'votacao', 'voto', 'candidatura'
    id_externo        TEXT,
    -- ATENÇÃO: o payload NUNCA é gravado como veio da fonte. O dataset de
    -- candidaturas do TSE contém NR_CPF_CANDIDATO, e persistir o payload
    -- original criaria uma base de CPFs em claro de ~28 mil candidatos.
    -- A escrita passa por uma allowlist de campos por (fonte, recurso) —
    -- allowlist, não denylist: um campo novo na origem entra como ignorado,
    -- e não como vazamento.
    payload           JSONB NOT NULL,
    campos_redigidos  TEXT[] NOT NULL DEFAULT '{}',
    payload_hash      CHAR(64) NOT NULL,
    coletado_em       TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Dedup por conteúdo: recoletar um payload idêntico não gera nova linha,
    -- mantendo o crescimento proporcional às mudanças reais nas fontes e não
    -- ao número de execuções. NULLS NOT DISTINCT é obrigatório aqui: com a
    -- semântica padrão do Postgres, NULL != NULL, e todo registro sem
    -- id_externo escaparia da deduplicação silenciosamente.
    UNIQUE NULLS NOT DISTINCT (fonte, recurso, id_externo, payload_hash)
);

CREATE INDEX idx_payload_bruto_execucao ON staging.payload_bruto (execucao_id);
CREATE INDEX idx_payload_bruto_coletado ON staging.payload_bruto (coletado_em);

-- Retenção: o histórico bruto serve para reprocessar normalização com bug
-- descoberto tarde, não como arquivo permanente. Limpeza de registros com
-- mais de 90 dias roda junto do job incremental.

-- ----------------------------------------------------------------------------
-- staging.registro_rejeitado: quarentena — o oposto de descartar em silêncio
-- ----------------------------------------------------------------------------
-- Um voto cujo parlamentar ainda não foi vinculado viola a FK de
-- voto_nominal. Tratar isso com "ON CONFLICT DO NOTHING" ou try/catch por
-- registro faria o voto desaparecer sem rastro — numa plataforma de
-- transparência, omitir voto em silêncio é pior que falhar alto. Aqui o
-- registro fica visível como item de trabalho, com o payload para reprocesso.

CREATE TABLE staging.registro_rejeitado (
    id            BIGSERIAL PRIMARY KEY,
    execucao_id   BIGINT NOT NULL REFERENCES ingestao_execucao(id),
    fonte         fonte_enum NOT NULL,
    recurso       TEXT NOT NULL,
    id_externo    TEXT,
    motivo        motivo_rejeicao_enum NOT NULL,
    detalhe       TEXT,
    payload       JSONB NOT NULL,   -- redigido pela mesma allowlist
    rejeitado_em  TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolvido_em  TIMESTAMPTZ
);

-- Alimenta a métrica de negócio "registros em quarentena por fonte e motivo",
-- cujo valor esperado é zero — qualquer valor acima disso é alerta.
CREATE INDEX idx_rejeitado_pendente
    ON staging.registro_rejeitado (fonte, motivo)
    WHERE resolvido_em IS NULL;
