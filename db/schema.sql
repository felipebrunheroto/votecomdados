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
--            Proposicao, ProposicaoTema, ProposicaoAutor, Votacao,
--            MandatoExercicio, VotoNominal
-- Referência: MapeamentoVoto e MapeamentoSituacao (origem -> enum),
--            CoberturaFonte (limites da fonte)
-- Histórico interno (não publicado): VotoNominalHistorico, ProposicaoHistorico
-- Projeção de leitura: PerfilLeitura (reconstruída ao fim de cada ingestão)
-- Dados abertos (schema dados_abertos): recorte publicável, sem dado pessoal
-- Controle de ingestão: IngestaoExecucao (watermark auditável)
-- Schema bruto (staging): PayloadBruto (JSONB redigido), RegistroRejeitado
--
-- AUSÊNCIA É CALCULADA, NÃO INGERIDA: nenhuma fonte publica "faltou". Os votos
-- AUSENTE e LICENCIADO saem do cruzamento entre votacao e mandato_exercicio, e
-- vêm marcados com origem_registro = 'DERIVADO'.
--
-- Requer PostgreSQL 15+ (usa UNIQUE ... NULLS NOT DISTINCT).
--
-- Ver docs/ARQUITETURA.md para o papel de cada bloco no pipeline e
-- docs/REVISAO_ARQUITETURA.md para as decisões que moldaram este schema.
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid(), hmac()
CREATE EXTENSION IF NOT EXISTS pg_trgm;    -- busca fuzzy / ILIKE acelerada
CREATE EXTENSION IF NOT EXISTS unaccent;   -- normalização de acentuação
CREATE EXTENSION IF NOT EXISTS btree_gist; -- EXCLUDE de períodos por parlamentar

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
-- AUSENTE e LICENCIADO NÃO são publicados por nenhuma fonte: `votacoesVotos`
-- lista apenas quem registrou voto (em 2026 há cinco rótulos — Sim, Não,
-- Abstenção, Artigo 17, Obstrução — e mediana de 398 linhas para 513 cadeiras).
-- Os dois são DERIVADOS do cruzamento com mandato_exercicio; ver origem_registro
-- em voto_nominal. Exibir só quem votou omitiria a ausência em silêncio, que é
-- o modo de falha que este schema inteiro existe para impedir.
-- SECRETO, PRESENTE_NAO_VOTOU e AUSENCIA_JUSTIFICADA vieram do Senado, e não
-- são cauda: juntos são 12,3 mil das 22 mil linhas da amostra verificada.
--   SECRETO             -> participou de votação secreta; a escolha não é
--                          publicada. Forçá-lo a SIM/NAO seria inventar; a
--                          AUSENTE, caluniar por omissão.
--   PRESENTE_NAO_VOTOU  -> estava na sessão e não registrou voto. Não é falta.
--   AUSENCIA_JUSTIFICADA-> ausência por trabalho da Casa (missão, atividade
--                          parlamentar). Diferente de licença e de falta.
-- ART_17 tem nome da Câmara e serve às duas Casas: no Senado o equivalente é o
-- art. 51 do RISF. O rótulo literal fica em voto_origem, e a UI mostra o dele.
-- VOTO_EM_SEPARADO e BRANCO vieram da Alesp, que os publica como CÓDIGO:
--   VOTO_EM_SEPARADO -> votou apresentando parecer escrito divergente do
--                       relator (código 'S', 2.130 votos). A fonte não diz se
--                       o divergente é favorável ou contrário ao projeto, e a
--                       amostra tem os dois — inferir a direção seria inventar.
--   BRANCO           -> voto em branco (código 'B', 186 votos). A Alesp o conta
--                       SEPARADO da abstenção (código 'A', 164), e colapsar os
--                       dois apagaria uma distinção que a fonte faz.
CREATE TYPE tipo_voto_enum AS ENUM (
    'SIM', 'NAO', 'ABSTENCAO', 'BRANCO',
    'AUSENTE', 'LICENCIADO', 'AUSENCIA_JUSTIFICADA', 'PRESENTE_NAO_VOTOU',
    'SECRETO', 'OBSTRUCAO', 'VOTO_EM_SEPARADO', 'ART_17'
);

-- FONTE   -> a Casa publicou esta linha; voto_origem traz o rótulo literal.
-- DERIVADO-> nós calculamos a linha; não existe rótulo de origem para citar.
CREATE TYPE origem_registro_enum AS ENUM ('FONTE', 'DERIVADO');

-- Situação do parlamentar na Casa, normalizada. O vocabulário da origem é
-- inconsistente ('Exercício' e 'Licença' capitalizados, 'SUPLENCIA' e
-- 'FIM_MANDATO' em caixa alta, além de nulos), então vale a mesma regra do
-- voto: enum normalizado + string original preservada + tabela de mapeamento.
CREATE TYPE situacao_exercicio_enum AS ENUM (
    'EXERCICIO', 'LICENCA', 'SUPLENCIA', 'CONVOCADO', 'FIM_MANDATO', 'VACANCIA'
);

CREATE TYPE condicao_eleitoral_enum AS ENUM ('TITULAR', 'SUPLENTE');
CREATE TYPE tipo_votacao_enum AS ENUM ('NOMINAL', 'SIMBOLICA');
-- NAO_INFORMADO existe porque a fonte usa sentinela, e traduzi-la para APTO
-- seria afirmar em nome do TSE. Verificado em 31/08/2026: DS_SITUACAO_CANDIDATURA
-- vem '#NE' (não especificado) em 100% das 20.809 candidaturas de 2026 — o
-- registro ainda está sendo julgado. Sem este valor, a plataforma diria ao
-- eleitor que todo candidato está apto, sem que ninguém tenha dito isso.
CREATE TYPE status_candidatura_enum AS ENUM (
    'NAO_INFORMADO', 'DEFERIDO', 'INDEFERIDO', 'CASSADO', 'RENUNCIA', 'APTO', 'INAPTO'
);
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
    -- Parlamentar que NÃO é candidato em 2026: esperado, não é defeito.
    -- Sem esta distinção, a maioria das ~398 linhas de cada votação nominal
    -- cairia em POLITICO_NAO_RESOLVIDO e a métrica de quarentena — cujo valor
    -- esperado é zero — nasceria com dezenas de milhares de linhas, tornando
    -- o alerta inútil no primeiro dia. Gravado UMA VEZ POR PARLAMENTAR
    -- (recurso = 'parlamentar'), nunca por voto, e nunca alertado.
    'FORA_DA_COORTE',
    'VOTACAO_DESCONHECIDA',
    'PROPOSICAO_DESCONHECIDA',
    'VALOR_VOTO_NAO_MAPEADO',   -- string de voto ausente em mapeamento_voto
    'SITUACAO_NAO_MAPEADA',     -- situação de exercício ausente em mapeamento_situacao
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
    -- A decisão HUMANA é a discricionária — é justamente ela que precisa de
    -- autor e data. Um booleano sozinho registra que alguém decidiu, não quem
    -- nem quando, e com curador único isso importa mais, não menos: é o que
    -- separa curadoria auditável de UPDATE manual em produção.
    revisado_por          TEXT,
    revisado_em           TIMESTAMPTZ,
    -- Impede marcar como revisado sem dizer por quem e quando.
    CONSTRAINT revisao_auditavel CHECK (
        (revisado_manualmente = false AND revisado_por IS NULL
                                      AND revisado_em IS NULL)
     OR (revisado_manualmente = true  AND revisado_por IS NOT NULL
                                      AND revisado_em IS NOT NULL)
    ),
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
    -- Votação secreta É nominal: a Casa registra QUEM participou, não COMO
    -- cada um votou. No Senado são 53% das votações de plenário, então tratar
    -- isso como detalhe deixaria mais da metade do histórico ambíguo.
    -- Verificado: o rótulo 'Votou' aparece exclusivamente com secreta = true.
    secreta          BOOLEAN NOT NULL DEFAULT false,
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
-- **477 rótulos distintos** em 226 mil votos de comissão, texto livre, com
-- erros de digitação ("proejto") e frases inteiras. Um `switch` em código
-- seria insustentável.
--
-- Mas a chave da ALESP NÃO é esse texto. O arquivo publica DOIS campos:
-- <TipoVoto>, código de uma letra com 8 valores documentados pela própria
-- Alesp, e <Voto>, que a documentação dela chama de "descrição do tipo do
-- voto". Mapear pelo texto deixaria ~1% dos votos em quarentena permanente,
-- e crescendo — texto livre cresce. Mapear pelo código é usar a classificação
-- DA FONTE em vez de fabricar uma nossa. Ver voto_nominal.voto_origem_codigo.

INSERT INTO mapeamento_voto (fonte, valor_origem, voto, observacao) VALUES
    ('CAMARA', 'Sim',         'SIM',       NULL),
    ('CAMARA', 'Não',         'NAO',       NULL),
    ('CAMARA', 'Abstenção',   'ABSTENCAO', NULL),
    ('CAMARA', 'Obstrução',   'OBSTRUCAO', 'Obstrução é manobra regimental de orientação de bancada, não um voto contrário ao mérito.'),
    ('CAMARA', 'Artigo 17',   'ART_17',    'O presidente da Casa só vota em situações previstas no regimento.'),
    -- ALESP: os 8 códigos de <TipoVoto>, com a contagem verificada nos
    -- 226.067 votos de comissão reais (31/08/2026).
    --
    -- F e P são AMBOS favoráveis, e a distinção é sobre O QUE se vota: F é
    -- favorável ao PARECER do relator, P é favorável ao PROJETO. A Casa usa um
    -- par ou o outro numa mesma deliberação — F/C ou P/T —, e C e T nunca
    -- coexistem (0 casos em 29.923 deliberações).
    --
    -- Quando F e P coexistem (36 deliberações, 0,12%) eles podem ser OPOSTOS:
    -- o texto "Favorável ao projeto e contrário ao parecer" vem codificado
    -- como P, ao lado de um F. Esses votos vão para quarentena, e a decisão é
    -- do job — a ambiguidade é da DELIBERAÇÃO, não do código.
    ('ALESP',  'F', 'SIM',       'Favorável ao parecer do relator, em comissão. Não é votação de plenário.'),
    ('ALESP',  'P', 'SIM',       'Favorável ao projeto, em comissão. Não é votação de plenário.'),
    ('ALESP',  'C', 'NAO',       'Contrário ao parecer do relator, em comissão. Não é votação de plenário.'),
    ('ALESP',  'T', 'NAO',       'Contrário ao projeto, em comissão. Não é votação de plenário.'),
    ('ALESP',  'A', 'ABSTENCAO', 'Abstenção em comissão.'),
    ('ALESP',  'B', 'BRANCO',    'Voto em branco. A Alesp o conta separado da abstenção.'),
    ('ALESP',  'S', 'VOTO_EM_SEPARADO', 'Voto em separado: o parlamentar votou apresentando parecer escrito divergente do relator. A fonte não diz se o divergente é favorável ou contrário ao projeto.'),

    -- SENADO: os 13 rótulos verificados em 22 mil linhas reais (31/08/2026).
    -- Ao contrário da Câmara, o Senado publica a bancada INTEIRA em cada
    -- votação — por isso ausência e licença aqui são FATO da fonte, não
    -- derivação nossa.
    ('SENADO', 'Sim',        'SIM',       NULL),
    ('SENADO', 'Não',        'NAO',       NULL),
    ('SENADO', 'Abstenção',  'ABSTENCAO', NULL),
    ('SENADO', 'Votou',      'SECRETO',   'Votação secreta: a Casa registra que o parlamentar participou, não como votou.'),
    ('SENADO', 'P-NRV',      'PRESENTE_NAO_VOTOU', 'Presente na sessão, não registrou voto — não é ausência.'),
    ('SENADO', 'AP',         'AUSENCIA_JUSTIFICADA', 'Ausência justificada por atividade parlamentar.'),
    ('SENADO', 'MIS',        'AUSENCIA_JUSTIFICADA', 'Ausência justificada por missão da Casa, no país ou no exterior.'),
    ('SENADO', 'LS',         'LICENCIADO', 'Licença para tratamento de saúde.'),
    ('SENADO', 'LP',         'LICENCIADO', 'Licença particular.'),
    ('SENADO', 'LAP',        'LICENCIADO', 'Licença paternidade ou ao adotante.'),
    ('SENADO', 'NCom',       'AUSENTE',    'Não compareceu à sessão.'),
    ('SENADO', 'Presidente (art. 51 RISF)', 'ART_17', 'O presidente da sessão não vota, salvo nas hipóteses do regimento. Equivale ao Art. 17 da Câmara.');

-- DELIBERADAMENTE NÃO MAPEADO na ALESP: o código 'O' ("Outros"), documentado
-- pela Alesp e ausente dos 226.067 votos atuais. É a própria fonte dizendo
-- "não classificado" — traduzi-lo seria inventar. Se aparecer, vai para
-- quarentena. O invariante T61 fixa esse conjunto.

-- DELIBERADAMENTE NÃO MAPEADO: o rótulo 'NA' do Senado ("Dispositivo não
-- citado", 7 ocorrências na amostra) não é voto — é anotação de que o
-- dispositivo não foi citado na votação. Não há tradução honesta para o enum,
-- então ele vai para quarentena (VALOR_VOTO_NAO_MAPEADO), que é a regra do
-- projeto: preferimos não classificar a classificar errado.
-- O invariante T51 fixa esse conjunto: se a fonte trouxer um rótulo novo, ele
-- falha, em vez de virar quarentena silenciosa em produção.

-- Demais rótulos: acrescentar aqui após revisão de quem entende de processo
-- legislativo. O que não estiver mapeado vai para quarentena, visível.

-- ----------------------------------------------------------------------------
-- MapeamentoSituacao: vocabulário de situação da Casa -> enum, versionado
-- ----------------------------------------------------------------------------
-- Mesma regra do mapeamento_voto, e pelo mesmo motivo: o vocabulário da fonte
-- é inconsistente e pode mudar sem aviso. O que não estiver mapeado vai para
-- quarentena (SITUACAO_NAO_MAPEADA) em vez de ser adivinhado — errar aqui
-- significa marcar como ausente quem estava licenciado, ou vice-versa.

CREATE TABLE mapeamento_situacao (
    id             BIGSERIAL PRIMARY KEY,
    fonte          fonte_enum NOT NULL,
    valor_origem   TEXT NOT NULL,
    situacao       situacao_exercicio_enum NOT NULL,
    -- Só quem está em EXERCICIO ou LICENCA compõe o universo de uma votação.
    -- Suplente não convocado, mandato encerrado e vacância não geram linha de
    -- voto alguma: não é ausência, é não ser parlamentar naquele dia.
    conta_no_universo BOOLEAN NOT NULL DEFAULT false,
    observacao     TEXT,
    vigente_desde  DATE NOT NULL DEFAULT CURRENT_DATE,
    UNIQUE (fonte, valor_origem)
);

-- Valores observados na API da Câmara (/deputados/{id}/historico) em 30/08/2026.
INSERT INTO mapeamento_situacao (fonte, valor_origem, situacao, conta_no_universo, observacao) VALUES
    ('CAMARA', 'Exercício',   'EXERCICIO',   true,  NULL),
    ('CAMARA', 'Licença',     'LICENCA',     true,  'Licença registrada pela Casa: a falta ao painel não é escolha política.'),
    ('CAMARA', 'CONVOCADO',   'CONVOCADO',   false, 'Suplente convocado; o exercício efetivo entra como período próprio.'),
    ('CAMARA', 'SUPLENCIA',   'SUPLENCIA',   false, 'Suplente não empossado: não compõe o universo da votação.'),
    ('CAMARA', 'FIM_MANDATO', 'FIM_MANDATO', false, 'Mandato encerrado.'),
    ('CAMARA', 'VACANCIA',    'VACANCIA',    false, 'Cadeira vaga.');

-- ----------------------------------------------------------------------------
-- MandatoExercicio: quem era parlamentar, em que condição, em cada período
-- ----------------------------------------------------------------------------
-- É a tabela que torna possível dizer que alguém FALTOU. Sem ela, a plataforma
-- só consegue listar quem votou — e um parlamentar que faltou a 40% das
-- votações apareceria com histórico aparentemente limpo.
--
-- Fonte: /deputados/{id}/historico da Câmara, que devolve a linha do tempo de
-- situação com data. São ~7.9 mil chamadas uma única vez (o cadastro completo
-- desde 1934), depois só o incremental dos ~600 em atividade — nada parecido
-- com o N+1 que o B3 eliminou. Verificado que o histórico alcança legislaturas
-- anteriores a 2001, que é o limite dos votos nominais.

CREATE TABLE mandato_exercicio (
    id               BIGSERIAL PRIMARY KEY,
    politico_id      UUID NOT NULL REFERENCES politico(id) ON DELETE CASCADE,
    casa             casa_legislativa_enum NOT NULL,
    situacao         situacao_exercicio_enum NOT NULL,
    -- Mesma garantia do voto_origem: a string literal da fonte é o fato; o
    -- enum ao lado é interpretação nossa.
    situacao_origem  TEXT NOT NULL,
    condicao         condicao_eleitoral_enum,
    inicio           DATE NOT NULL,
    fim              DATE,          -- NULL = período ainda vigente
    url_fonte        TEXT,
    CONSTRAINT periodo_coerente CHECK (fim IS NULL OR fim >= inicio),
    -- Períodos sobrepostos tornariam o universo da votação ambíguo: a mesma
    -- pessoa apareceria em EXERCICIO e em LICENCA no mesmo dia, e a derivação
    -- produziria AUSENTE onde havia licença. O banco recusa a sobreposição em
    -- vez de deixar o pipeline escolher.
    EXCLUDE USING gist (
        politico_id WITH =,
        casa WITH =,
        daterange(inicio, fim, '[)') WITH &&
    )
);

CREATE INDEX idx_mandato_politico ON mandato_exercicio (politico_id);
-- Consulta central da derivação: quem estava na Casa na data da votação.
CREATE INDEX idx_mandato_universo
    ON mandato_exercicio (casa, inicio, fim)
    WHERE situacao IN ('EXERCICIO', 'LICENCA');

-- ----------------------------------------------------------------------------
-- VotoNominal: o voto individual de cada parlamentar em uma votação nominal
-- ----------------------------------------------------------------------------

CREATE TABLE voto_nominal (
    id           BIGSERIAL PRIMARY KEY,
    votacao_id   BIGINT NOT NULL REFERENCES votacao(id) ON DELETE CASCADE,
    politico_id  UUID NOT NULL REFERENCES politico(id) ON DELETE CASCADE,
    voto         tipo_voto_enum NOT NULL,
    -- String literal da fonte, preservada sempre que existir. O enum acima é
    -- uma interpretação nossa; este campo é o fato. Sem ele, um erro de
    -- mapeamento descoberto meses depois seria irrecuperável a partir do
    -- dado curado, e a UI não poderia mostrar o rótulo oficial ao lado do
    -- normalizado. É NULL apenas em linha derivada, onde não há rótulo de
    -- origem a citar — e o CHECK abaixo garante que essa é a única exceção.
    voto_origem  TEXT,
    -- FONTE: a Casa publicou a linha. DERIVADO: nós a calculamos cruzando a
    -- votação com mandato_exercicio (ausência e licença, que nenhuma fonte
    -- publica). A UI é obrigada a marcar a diferença — apresentar cálculo
    -- nosso como registro oficial seria o mesmo erro que o voto_origem existe
    -- para impedir, só que em outra casa decimal.
    origem_registro origem_registro_enum NOT NULL DEFAULT 'FONTE',
    -- Código de voto da fonte, quando ela publica um SEPARADO do rótulo
    -- textual. Hoje só a Alesp: o arquivo de votações de comissão traz
    -- <TipoVoto> (8 códigos documentados) e <Voto> (477 textos livres, que a
    -- própria documentação chama de "descrição do tipo do voto").
    --
    -- Os dois são fato, e a coluna separa o que a fonte AFIRMA do que ela
    -- DESCREVE: o código é a chave estável de mapeamento_voto, o texto é o que
    -- a UI mostra em "registrado como". Mapear pelo texto deixaria ~1% dos
    -- votos em quarentena permanente — e crescendo, porque texto livre cresce.
    -- NULL para Câmara e Senado, que publicam um campo só.
    voto_origem_codigo TEXT,
    -- Note que AUSENTE/LICENCIADO NÃO estão proibidos em linha de FONTE: se o
    -- Senado publicar uma ausência com rótulo próprio, ela entra como fato,
    -- com o rótulo preservado. O que o CHECK impede é linha derivada fingindo
    -- ter origem, e voto declarado perdendo o rótulo.
    CONSTRAINT voto_origem_coerente CHECK (
        (origem_registro = 'FONTE'    AND voto_origem IS NOT NULL)
     OR (origem_registro = 'DERIVADO' AND voto_origem IS NULL
                                      AND voto IN ('AUSENTE', 'LICENCIADO'))
    ),
    -- Linha derivada não tem rótulo de origem a citar — nem texto, nem código.
    CONSTRAINT voto_origem_codigo_coerente CHECK (
        origem_registro = 'FONTE' OR voto_origem_codigo IS NULL
    ),
    UNIQUE (votacao_id, politico_id)
);

CREATE INDEX idx_votonominal_politico ON voto_nominal (politico_id);
CREATE INDEX idx_votonominal_votacao ON voto_nominal (votacao_id);

COMMENT ON COLUMN voto_nominal.voto_origem_codigo IS
    'Código de voto da fonte, quando ela publica um separado do rótulo textual. '
    'Hoje só a Alesp (<TipoVoto>). É a chave de mapeamento_voto para essa fonte.';

-- O histórico de alteração de voto (correção retroativa) fica logo após
-- ingestao_execucao, de quem depende por FK. Ver VotoNominalHistorico.
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

-- Qual Casa legislativa corresponde a um mandato. É o que permite mostrar a
-- cobertura CERTA para cada pessoa: um senador não deve ler a cobertura da
-- Câmara, e era exatamente isso que acontecia antes desta coluna existir.
-- Deputado estadual só resolve para ALESP em SP; nos demais estados não há
-- casa integrada, e a linha genérica de "fora do escopo" é que se aplica.
CREATE FUNCTION casa_do_mandato(p_cargo cargo_enum, p_uf CHAR(2))
RETURNS casa_legislativa_enum
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$
    SELECT CASE
        WHEN p_cargo = 'DEPUTADO_FEDERAL' THEN 'CAMARA'::casa_legislativa_enum
        WHEN p_cargo IN ('SENADOR', 'PRIMEIRO_SUPLENTE', 'SEGUNDO_SUPLENTE')
             THEN 'SENADO'::casa_legislativa_enum
        WHEN p_cargo IN ('DEPUTADO_ESTADUAL', 'DEPUTADO_DISTRITAL') AND p_uf = 'SP'
             THEN 'ALESP'::casa_legislativa_enum
        ELSE NULL
    END
$$;

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
    -- NULL = a regra não é de uma Casa específica (é o caso de `candidatura`,
    -- que vem do TSE e vale para a esfera inteira).
    --
    -- Sem esta coluna o modelo assumia UMA fonte por (esfera, uf, recurso) — e
    -- a esfera federal tem DUAS Casas, com coberturas diferentes: voto nominal
    -- da Câmara existe desde 2001, o do Senado desde 1991. A linha única dizia
    -- "desde 2001" para todo mundo, o que era falso para senadores nos dois
    -- sentidos. Achado ao integrar o Senado (A12).
    casa              casa_legislativa_enum,
    -- Impede a combinação incoerente que causaria mensagem errada na UI:
    -- só há data de início quando de fato cobrimos o recurso.
    CONSTRAINT cobertura_coerente CHECK (
        (status = 'DISPONIVEL'  AND fonte IS NOT NULL AND disponivel_desde IS NOT NULL)
     OR (status <> 'DISPONIVEL' AND disponivel_desde IS NULL)
    ),
    UNIQUE NULLS NOT DISTINCT (esfera, uf, casa, recurso)
);

INSERT INTO cobertura_fonte (esfera, uf, casa, recurso, status, fonte, disponivel_desde, observacao) VALUES
    -- Trajetória eleitoral: TSE cobre os três níveis de forma uniforme, e não
    -- é de nenhuma Casa — por isso casa NULL.
    ('FEDERAL',   NULL, NULL, 'candidatura',      'DISPONIVEL', 'TSE',    '1994-01-01', 'Candidaturas federais em dados abertos do TSE.'),
    ('ESTADUAL',  NULL, NULL, 'candidatura',      'DISPONIVEL', 'TSE',    '1994-01-01', 'Candidaturas estaduais em dados abertos do TSE.'),
    ('MUNICIPAL', NULL, NULL, 'candidatura',      'DISPONIVEL', 'TSE',    '1996-01-01', 'Candidaturas municipais (vereador, prefeito) em dados abertos do TSE.'),

    -- Câmara dos Deputados.
    ('FEDERAL',   NULL, 'CAMARA', 'proposicao',       'DISPONIVEL', 'CAMARA', '1934-01-01', 'Arquivos anuais de proposições disponíveis desde 1934.'),
    ('FEDERAL',   NULL, 'CAMARA', 'votacao_plenario', 'DISPONIVEL', 'CAMARA', '1990-01-01', 'Metadados e placar agregado das votações de plenário.'),
    ('FEDERAL',   NULL, 'CAMARA', 'voto_nominal',     'DISPONIVEL', 'CAMARA', '2001-01-01', 'Votos nominais individuais de plenário só existem a partir de 2001; mandatos anteriores não têm registro individual publicado.'),
    ('FEDERAL',   NULL, 'CAMARA', 'exercicio_parlamentar', 'DISPONIVEL', 'CAMARA', '2001-01-01', 'Histórico de situação (exercício, licença, suplência) por parlamentar. É o que permite derivar ausência e licença, que a fonte não publica como voto.'),

    -- Senado Federal. Verificado no spike de 31/08/2026: o voto nominal
    -- alcança 1991 — dez anos a mais que a Câmara — e a fonte publica a
    -- bancada inteira em cada votação, o que dispensa a derivação de ausência.
    ('FEDERAL',   NULL, 'SENADO', 'proposicao',       'DISPONIVEL', 'SENADO', '1991-01-01', 'Processos legislativos do Senado. A autoria vem como texto e é ligada ao parlamentar por consulta por autor.'),
    ('FEDERAL',   NULL, 'SENADO', 'votacao_plenario', 'DISPONIVEL', 'SENADO', '1991-01-01', 'Votações nominais de plenário do Senado e do Congresso Nacional.'),
    ('FEDERAL',   NULL, 'SENADO', 'voto_nominal',     'DISPONIVEL', 'SENADO', '1991-01-01', 'Votos individuais desde 1991. Em 53% das votações a deliberação é secreta: a Casa registra quem participou, não como votou — o voto aparece como SECRETO, não como ausência.'),
    ('FEDERAL',   NULL, 'SENADO', 'exercicio_parlamentar', 'DISPONIVEL', 'SENADO', '1991-01-01', 'A própria votação já traz a bancada inteira, com licença e ausência declaradas pela Casa. Não há derivação nossa no Senado.'),

    -- Atuação legislativa estadual em SP: piloto do MVP.
    -- Datas conferidas nos arquivos em 31/08/2026. As duas anteriores diziam
    -- 1995 e erravam em sentidos OPOSTOS: a de comissão afirmava onze anos de
    -- cobertura inexistente, a de propositura escondia vinte e cinco que
    -- existem. Número plausível nunca conferido — o mesmo defeito que o Senado
    -- revelou em `voto_nominal` federal.
    ('ESTADUAL',  'SP', 'ALESP', 'proposicao',       'DISPONIVEL', 'ALESP',  '1970-09-23', 'Proposituras e autoria no portal de dados abertos da Alesp. A série alcança 1970, mas é rala antes de 1995: 497 matérias em 25 anos, contra 278 mil no total.'),
    ('ESTADUAL',  'SP', 'ALESP', 'votacao_comissao', 'DISPONIVEL', 'ALESP',  '2006-02-15', 'Votos individuais em comissões permanentes da Alesp, desde fevereiro de 2006. A Casa publica o voto de cada membro da comissão, e não a lista de quem faltou — ausência em comissão não é derivada.'),
    -- A Alesp PUBLICA votação nominal de plenário — verificado na API do
    -- portal (/sessoes-plenarias/{id}/votacoes) —, mas o registro de quem
    -- votou como vem dentro de um PDF por votação, com imagem embutida.
    -- Existe e não é legível por máquina. Dizer "não publica" faria o eleitor
    -- concluir que o registro não existe, que é afirmação diferente e falsa.
    ('ESTADUAL',  'SP', 'ALESP', 'voto_nominal',     'NAO_PUBLICADO_PELA_FONTE', 'ALESP', NULL, 'A Alesp publica as votações nominais de plenário apenas como PDF por votação, um por deliberação, sem dado estruturado de quem votou como. O registro existe e não é legível por máquina; a limitação é da forma de publicação da fonte, não da plataforma.'),

    -- Demais estados: fora do MVP (a linha sem UF e sem casa é o fallback).
    ('ESTADUAL',  NULL, NULL, 'proposicao',       'FORA_DO_ESCOPO_MVP', NULL, NULL, 'Nesta versão só a Assembleia de São Paulo está integrada. As demais assembleias ainda não foram cobertas.'),
    ('ESTADUAL',  NULL, NULL, 'voto_nominal',     'FORA_DO_ESCOPO_MVP', NULL, NULL, 'Nesta versão só a Assembleia de São Paulo está integrada. As demais assembleias ainda não foram cobertas.'),

    -- Municipal: fora do MVP, e sem fonte padronizada mesmo depois.
    ('MUNICIPAL', NULL, NULL, 'proposicao',       'FORA_DO_ESCOPO_MVP', NULL, NULL, 'A atuação em câmaras municipais não é coberta nesta versão. As 5.570 câmaras não publicam dados legislativos em formato padronizado.'),
    ('MUNICIPAL', NULL, NULL, 'voto_nominal',     'FORA_DO_ESCOPO_MVP', NULL, NULL, 'A atuação em câmaras municipais não é coberta nesta versão. As 5.570 câmaras não publicam dados legislativos em formato padronizado.');

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
-- VotoNominalHistorico: correção retroativa registrada, não publicada
-- ----------------------------------------------------------------------------
-- Se a fonte corrigir um voto meses depois, o upsert sobrescreve e a versão
-- anterior desaparece. Para o público isso é irrelevante — o que vale é o
-- registro oficial atual. Para quem opera a plataforma, não: é a única forma
-- de responder "isto mudou porque a Câmara corrigiu, ou porque nosso
-- mapeamento estava errado?".
--
-- Fora do contrato da API pública, de propósito. É ferramenta de diagnóstico.

CREATE TABLE voto_nominal_historico (
    id                    BIGSERIAL PRIMARY KEY,
    voto_nominal_id       BIGINT NOT NULL REFERENCES voto_nominal(id) ON DELETE CASCADE,
    voto_anterior         tipo_voto_enum NOT NULL,
    voto_origem_anterior  TEXT,
    voto_novo             tipo_voto_enum NOT NULL,
    voto_origem_novo      TEXT,
    -- Qual execução do worker fez a alteração. NULL quando veio de UPDATE
    -- manual — que é exatamente o caso em que saber disso mais importa.
    execucao_id           BIGINT REFERENCES ingestao_execucao(id),
    alterado_em           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT mudanca_real CHECK (
        voto_anterior IS DISTINCT FROM voto_novo
     OR voto_origem_anterior IS DISTINCT FROM voto_origem_novo
    )
);

CREATE INDEX idx_voto_historico_voto ON voto_nominal_historico (voto_nominal_id);
CREATE INDEX idx_voto_historico_data ON voto_nominal_historico (alterado_em);

-- Trigger, e não responsabilidade do worker: o histórico precisa valer também
-- para o UPDATE manual da curadoria, que é justamente o que ninguém lembraria
-- de instrumentar. O worker anuncia sua execução com
-- `SET LOCAL votecomdados.execucao_id = '<id>'`; sem isso, a coluna fica NULL
-- e a alteração aparece como manual, que é a leitura correta.

CREATE FUNCTION registrar_alteracao_voto() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.voto IS DISTINCT FROM OLD.voto
       OR NEW.voto_origem IS DISTINCT FROM OLD.voto_origem THEN
        INSERT INTO voto_nominal_historico (
            voto_nominal_id, voto_anterior, voto_origem_anterior,
            voto_novo, voto_origem_novo, execucao_id
        ) VALUES (
            OLD.id, OLD.voto, OLD.voto_origem,
            NEW.voto, NEW.voto_origem,
            nullif(current_setting('votecomdados.execucao_id', true), '')::BIGINT
        );
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_voto_nominal_historico
    AFTER UPDATE ON voto_nominal
    FOR EACH ROW EXECUTE FUNCTION registrar_alteracao_voto();

-- ----------------------------------------------------------------------------
-- ProposicaoHistorico: ementa corrigida na origem, sem perder a versão antiga
-- ----------------------------------------------------------------------------
-- O A5 apontou que o upsert de proposicao nunca atualizava `ementa`: se a
-- Câmara corrigisse o texto, a plataforma exibiria a versão errada para
-- sempre. A correção tem duas metades — o upsert passa a atualizar o campo
-- (worker), e esta tabela guarda o que havia antes, para que "atualizar" não
-- vire "perder". Mesma regra do voto: registrado, não publicado.

CREATE TABLE proposicao_historico (
    id                 BIGSERIAL PRIMARY KEY,
    proposicao_id      BIGINT NOT NULL REFERENCES proposicao(id) ON DELETE CASCADE,
    campo              TEXT NOT NULL,   -- 'ementa', 'situacao_atual', ...
    valor_anterior     TEXT,
    valor_novo         TEXT,
    execucao_id        BIGINT REFERENCES ingestao_execucao(id),
    alterado_em        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT mudanca_real_proposicao CHECK (valor_anterior IS DISTINCT FROM valor_novo)
);

CREATE INDEX idx_proposicao_historico ON proposicao_historico (proposicao_id, alterado_em DESC);

CREATE FUNCTION registrar_alteracao_proposicao() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    execucao BIGINT := nullif(current_setting('votecomdados.execucao_id', true), '')::BIGINT;
BEGIN
    IF NEW.ementa IS DISTINCT FROM OLD.ementa THEN
        INSERT INTO proposicao_historico (proposicao_id, campo, valor_anterior, valor_novo, execucao_id)
        VALUES (OLD.id, 'ementa', OLD.ementa, NEW.ementa, execucao);
    END IF;
    IF NEW.situacao_atual IS DISTINCT FROM OLD.situacao_atual THEN
        INSERT INTO proposicao_historico (proposicao_id, campo, valor_anterior, valor_novo, execucao_id)
        VALUES (OLD.id, 'situacao_atual', OLD.situacao_atual, NEW.situacao_atual, execucao);
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_proposicao_historico
    AFTER UPDATE ON proposicao
    FOR EACH ROW EXECUTE FUNCTION registrar_alteracao_proposicao();

-- ----------------------------------------------------------------------------
-- PerfilLeitura: projeção desnormalizada da página de perfil (CQRS leve)
-- ----------------------------------------------------------------------------
-- A página de perfil exigia três consultas — político, trajetória e cobertura,
-- esta última com window function sobre join para resolver a precedência por
-- UF. Aqui vira um SELECT por chave primária.
--
-- A justificativa mudou depois que a volumetria foi definida, e vale registrar
-- honestamente: a original era carga (colapsar N consultas), e ~1.000 visitas
-- por dia não justificam isso. A que ficou de pé é o **p95**. Com o tráfego
-- espalhado por milhares de páginas, o cache de borda fica frio e quase toda
-- visita paga a consulta inteira num banco burstable — é o caminho que o alvo
-- da § 9 precisa cumprir.
--
-- O sistema já é CQRS de fato (escrita exclusiva do worker, leitura exclusiva
-- da API), então isto não introduz consistência eventual nova: a defasagem já
-- é diária e já está exposta em /meta/status.
--
-- RISCO ASSUMIDO: projeção que diverge da origem em silêncio. Mitigado por
-- reconstruir a partir de uma função única (abaixo) ao fim de cada ingestão, e
-- por invariante que compara projeção e origem (T40-T44).

CREATE TABLE perfil_leitura (
    politico_id                UUID PRIMARY KEY REFERENCES politico(id) ON DELETE CASCADE,
    nome_civil                 TEXT NOT NULL,
    nome_urna                  TEXT,
    possui_atuacao_legislativa BOOLEAN NOT NULL,
    -- Chaves em camelCase de propósito: são desserializadas direto nos records
    -- do domínio, que já são o contrato de docs/API.md. Traduzir de novo em
    -- Java só criaria um lugar a mais para a projeção divergir.
    trajetoria                 JSONB NOT NULL,
    cobertura                  JSONB NOT NULL,
    reconstruido_em            TIMESTAMPTZ NOT NULL DEFAULT now(),
    execucao_id                BIGINT REFERENCES ingestao_execucao(id)
);

-- Marca quem tem QUALQUER atuação legislativa registrada — federal (Câmara ou
-- Senado) ou estadual (Alesp). É o que separa, na busca e no build estático,
-- quem tem algo a mostrar de quem só tem a resposta "sem mandato legislativo
-- anterior" (ver comentário de possui_atuacao_legislativa em `politico`).
--
-- Recálculo total, não OR incremental: uma correção retroativa (poda de
-- autoria errada, exclusão de voto) precisa poder DESLIGAR a flag, e um job
-- que só liga bit a bit nunca desliga nada — a flag só cresceria, e passaria
-- a mentir na direção oposta.
--
-- Três sinais, nenhum redundante com os outros:
--
--   proposicao_autor   -> apresentou matéria (Câmara, Senado ou Alesp).
--   voto_nominal       -> a Casa registrou o voto (origem_registro = 'FONTE').
--                         Cobre Senado e Alesp, que NÃO alimentam
--                         mandato_exercicio (só a Câmara alimenta).
--   mandato_exercicio  -> exerceu mandato na Câmara, MESMO quando o único
--                         registro é uma ausência DERIVADA por nós. A
--                         ausência só é derivável porque a pessoa estava em
--                         exercício — marcá-la "sem atuação" apagaria um
--                         mandato inteiro, omissão pior que o excesso.
--
-- Roda ANTES de reconstruir_perfil_leitura(): a projeção copia esta coluna, e
-- reconstruí-la primeiro publicaria o valor antigo.
CREATE FUNCTION marcar_atuacao_legislativa() RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE
    afetados BIGINT;
BEGIN
    WITH atuantes AS (
        SELECT DISTINCT politico_id FROM proposicao_autor WHERE politico_id IS NOT NULL
        UNION
        SELECT DISTINCT politico_id FROM voto_nominal WHERE origem_registro = 'FONTE'
        UNION
        SELECT DISTINCT politico_id FROM mandato_exercicio
    ),
    calculado AS (
        SELECT p.id, (a.politico_id IS NOT NULL) AS atuante
          FROM politico p
          LEFT JOIN atuantes a ON a.politico_id = p.id
    ),
    atualizados AS (
        UPDATE politico p
           SET possui_atuacao_legislativa = c.atuante
          FROM calculado c
         WHERE c.id = p.id
           AND p.possui_atuacao_legislativa <> c.atuante
        RETURNING 1
    )
    SELECT count(*) INTO afetados FROM atualizados;

    RETURN afetados;
END $$;

-- Reconstrói a projeção inteira. Roda ao fim de cada ingestão bem-sucedida.
-- Reconstrução total, não incremental: a coorte tem ~28 mil pessoas e a
-- operação leva segundos — não vale a complexidade (e o risco de divergência)
-- de rastrear o que mudou.
CREATE FUNCTION reconstruir_perfil_leitura(p_execucao_id BIGINT DEFAULT NULL)
RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE
    afetados BIGINT;
BEGIN
    INSERT INTO perfil_leitura AS pl (
        politico_id, nome_civil, nome_urna,
        possui_atuacao_legislativa, trajetoria, cobertura,
        reconstruido_em, execucao_id
    )
    SELECT p.id, p.nome_civil, p.nome_urna, p.possui_atuacao_legislativa,
           coalesce(t.dados, '[]'::jsonb),
           coalesce(c.dados, '[]'::jsonb),
           now(), p_execucao_id
      FROM politico p
      -- Trajetória: da disputa mais recente para a mais antiga.
      LEFT JOIN LATERAL (
          SELECT jsonb_agg(jsonb_build_object(
                     'anoEleicao',   cand.ano_eleicao,
                     'cargo',        cand.cargo::text,
                     'esfera',       cand.esfera::text,
                     'uf',           cand.uf,
                     'municipio',    cand.municipio,
                     'partidoSigla', cand.partido_sigla,
                     'status',       cand.status::text,
                     'eleito',       cand.eleito)
                 ORDER BY cand.ano_eleicao DESC, cand.turno DESC) AS dados
            FROM candidatura cand
           WHERE cand.politico_id = p.id
      ) t ON true
      -- Cobertura pertinente à pessoa, resolvida em dois eixos:
      --
      --   UF   -> PRECEDÊNCIA: linha específica ganha da genérica, porque a
      --           pessoa tem uma UF por esfera e queremos UMA resposta.
      --   CASA -> PARTIÇÃO: quem foi deputado E senador tem DUAS coberturas
      --           federais legítimas, com datas de início diferentes (Câmara
      --           2001, Senado 1991). Colapsá-las mentiria sobre uma das duas.
      --
      -- O particionamento é pelo contexto da PESSOA (ctx), não pela linha:
      -- assim SP continua ganhando do fallback estadual, e as duas Casas
      -- federais continuam aparecendo lado a lado.
      LEFT JOIN LATERAL (
          SELECT jsonb_agg(jsonb_build_object(
                     'esfera',          x.esfera::text,
                     'uf',              x.uf,
                     'casa',            x.casa::text,
                     'recurso',         x.recurso,
                     'status',          x.status::text,
                     'disponivelDesde', x.disponivel_desde::text,
                     'observacao',      x.observacao)
                 ORDER BY x.esfera, x.casa NULLS FIRST, x.uf NULLS FIRST,
                          x.recurso) AS dados
            FROM (
                SELECT DISTINCT r.esfera, r.uf, r.casa, r.recurso, r.status,
                       r.disponivel_desde, r.observacao
                  FROM (
                      SELECT cf.*,
                             row_number() OVER (
                                 PARTITION BY cf.esfera, cf.recurso,
                                              ctx.casa, ctx.uf
                                 ORDER BY cf.uf NULLS LAST, cf.casa NULLS LAST
                             ) AS precedencia
                        FROM cobertura_fonte cf
                        JOIN (SELECT DISTINCT c.esfera, c.uf,
                                     casa_do_mandato(c.cargo, c.uf) AS casa
                                FROM candidatura c
                               WHERE c.politico_id = p.id) ctx
                          ON ctx.esfera = cf.esfera
                       WHERE (cf.uf IS NULL OR cf.uf = ctx.uf)
                         AND (cf.casa IS NULL OR cf.casa = ctx.casa)
                  ) r
                 WHERE r.precedencia = 1
                   AND r.recurso <> 'candidatura'
            ) x
      ) c ON true
    ON CONFLICT (politico_id) DO UPDATE SET
        nome_civil                 = EXCLUDED.nome_civil,
        nome_urna                  = EXCLUDED.nome_urna,
        possui_atuacao_legislativa = EXCLUDED.possui_atuacao_legislativa,
        trajetoria                 = EXCLUDED.trajetoria,
        cobertura                  = EXCLUDED.cobertura,
        reconstruido_em            = EXCLUDED.reconstruido_em,
        execucao_id                = EXCLUDED.execucao_id;

    GET DIAGNOSTICS afetados = ROW_COUNT;
    RETURN afetados;
END $$;

-- ============================================================================
-- dados_abertos: o que a plataforma publica de volta como dado aberto
-- ============================================================================
-- Recomendação R8. A plataforma pede confiança justamente na parte que ninguém
-- consegue conferir de fora: o CRUZAMENTO. Dizer "este deputado é esta
-- candidata" é uma afirmação nossa, e uma plataforma de transparência que não
-- pode ser auditada está pedindo fé, não mostrando dado.
--
-- Publicar o schema curado inverte isso: quem discordar de um vínculo pode
-- baixar o arquivo, refazer o cruzamento e apontar o erro. É o único jeito
-- honesto de sustentar a afirmação central do produto.
--
-- Modelado como VIEWS, e não como um script de export solto, por um motivo
-- específico: assim a exclusão de dado pessoal é VERIFICÁVEL (T48). Um script
-- de `\copy` com as colunas listadas à mão dependeria de ninguém errar ao
-- acrescentar coluna nova; aqui a lista é explícita e testada.
--
-- FICA DE FORA, de propósito:
--   * cpf_hmac         — pseudônimo de dado pessoal; publicá-lo não serve a
--                        nenhum uso de auditoria e só amplia a superfície.
--   * revisado_por     — identifica a pessoa curadora. Que a revisão ocorreu,
--                        e quando, é auditoria; quem a fez não acrescenta nada
--                        a quem confere o dado.
--   * schema staging   — payload bruto e quarentena são operação interna.
--   * *_historico      — diagnóstico do owner, não registro público (Q9).
--   * perfil_leitura   — projeção derivada; republicá-la seria redundância.

CREATE SCHEMA dados_abertos;

COMMENT ON SCHEMA dados_abertos IS
    'Recorte publicável do schema curado. Sem dado pessoal, sem operação interna.';

CREATE VIEW dados_abertos.politico AS
    SELECT id, nome_civil, nome_urna, nome_parlamentar, data_nascimento,
           genero, possui_atuacao_legislativa
      FROM politico;

CREATE VIEW dados_abertos.candidatura AS
    SELECT politico_id, sq_candidato_tse, ano_eleicao, turno, cargo, esfera,
           uf, municipio, codigo_municipio_tse, partido_sigla, status, eleito
      FROM candidatura;

-- A tabela mais importante do pacote: é o cruzamento, e é o que precisa de
-- auditoria externa. Vai com o método e o score, para que se possa separar o
-- vínculo determinístico do probabilístico — e conferir a cauda.
CREATE VIEW dados_abertos.identificador_externo AS
    SELECT politico_id, sistema, identificador, metodo_resolucao,
           score_confianca, revisado_manualmente, revisado_em
      FROM identificador_externo;

CREATE VIEW dados_abertos.proposicao AS
    SELECT id, casa, id_externo, sigla_tipo, numero, ano, ementa,
           data_apresentacao, situacao_atual, url_inteiro_teor, url_tramitacao
      FROM proposicao;

CREATE VIEW dados_abertos.proposicao_tema AS
    SELECT proposicao_id, tema FROM proposicao_tema;

CREATE VIEW dados_abertos.proposicao_autor AS
    SELECT proposicao_id, politico_id, autor_nome, autor_principal
      FROM proposicao_autor;

CREATE VIEW dados_abertos.votacao AS
    SELECT id, casa, id_externo, proposicao_id, data_votacao, descricao, tipo,
           ambito, secreta, aprovada, placar_sim, placar_nao, placar_abstencao,
           url_fonte
      FROM votacao;

-- origem_registro vai junto e não é detalhe: sem ele, quem baixar o arquivo
-- leria ausência calculada por nós como se fosse registro da Casa.
CREATE VIEW dados_abertos.voto_nominal AS
    SELECT votacao_id, politico_id, voto, voto_origem, origem_registro
      FROM voto_nominal;

CREATE VIEW dados_abertos.mandato_exercicio AS
    SELECT politico_id, casa, situacao, situacao_origem, condicao,
           inicio, fim, url_fonte
      FROM mandato_exercicio;

-- As tabelas de tradução vão junto porque sem elas o dump não é auditável:
-- é aqui que está registrado o que a plataforma decidiu que cada rótulo
-- significa, que é a parte editorial do trabalho.
CREATE VIEW dados_abertos.mapeamento_voto AS
    SELECT fonte, valor_origem, voto, observacao, vigente_desde
      FROM mapeamento_voto;

CREATE VIEW dados_abertos.mapeamento_situacao AS
    SELECT fonte, valor_origem, situacao, conta_no_universo, observacao, vigente_desde
      FROM mapeamento_situacao;

CREATE VIEW dados_abertos.cobertura_fonte AS
    SELECT esfera, uf, casa, recurso, status, fonte, disponivel_desde, observacao
      FROM cobertura_fonte;

-- Metadados do dump: sem eles o arquivo não é citável. Quem publicar uma
-- análise precisa poder dizer de qual instantâneo ela saiu, e quem contestar
-- precisa poder buscar o mesmo instantâneo — por isso a data de cada fonte e
-- as contagens saem junto do dado.
CREATE VIEW dados_abertos.manifesto AS
    SELECT
        current_date AS gerado_em,
        (SELECT jsonb_object_agg(fonte, ultima)
           FROM (SELECT fonte::text AS fonte, max(concluido_em) AS ultima
                   FROM ingestao_execucao
                  WHERE status = 'CONCLUIDA'
                  GROUP BY fonte) f) AS ultima_ingestao_por_fonte,
        (SELECT jsonb_object_agg(tabela, linhas) FROM (
             SELECT 'politico' AS tabela, count(*) AS linhas FROM politico
             UNION ALL SELECT 'candidatura', count(*) FROM candidatura
             UNION ALL SELECT 'identificador_externo', count(*) FROM identificador_externo
             UNION ALL SELECT 'proposicao', count(*) FROM proposicao
             UNION ALL SELECT 'votacao', count(*) FROM votacao
             UNION ALL SELECT 'voto_nominal', count(*) FROM voto_nominal
             UNION ALL SELECT 'mandato_exercicio', count(*) FROM mandato_exercicio
         ) c) AS linhas_por_tabela,
        (SELECT count(*) FROM voto_nominal WHERE origem_registro = 'DERIVADO')
            AS votos_derivados_por_nos,
        (SELECT count(*) FROM identificador_externo WHERE metodo_resolucao = 'FUZZY')
            AS vinculos_por_similaridade,
        (SELECT count(*) FROM identificador_externo
          WHERE metodo_resolucao = 'FUZZY' AND NOT revisado_manualmente)
            AS vinculos_fuzzy_sem_revisao_humana;

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

-- Alimenta a métrica de negócio "registros em quarentena por fonte e motivo".
-- O valor esperado é zero para TODOS os motivos MENOS 'FORA_DA_COORTE', que é
-- o registro esperado de quem simplesmente não é candidato em 2026 — e por
-- isso fica fora da regra de alerta:
--
--   SELECT fonte, motivo, count(*) FROM staging.registro_rejeitado
--    WHERE resolvido_em IS NULL AND motivo <> 'FORA_DA_COORTE'
--    GROUP BY 1, 2;                       -- qualquer linha aqui é alerta
--
CREATE INDEX idx_rejeitado_pendente
    ON staging.registro_rejeitado (fonte, motivo)
    WHERE resolvido_em IS NULL;

-- Sem isto, reprocessar uma fonte multiplicaria as mesmas linhas de quarentena
-- a cada execução — e 'FORA_DA_COORTE', que é gravado uma vez por parlamentar,
-- viraria uma linha por parlamentar POR EXECUÇÃO. NULLS NOT DISTINCT porque
-- id_externo é nulável: com a semântica padrão, todo registro sem id escaparia
-- da deduplicação (mesma armadilha do A4).
CREATE UNIQUE INDEX idx_rejeitado_unico_aberto
    ON staging.registro_rejeitado (fonte, recurso, id_externo, motivo)
    NULLS NOT DISTINCT
    WHERE resolvido_em IS NULL;
