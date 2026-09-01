-- ============================================================================
-- V3 — exercício parlamentar, voto derivado e trilhas de auditoria.
--
-- Origem: respostas do owner de 30/08/2026 (docs/REVISAO_ARQUITETURA.md,
-- § Perguntas respondidas), itens P1, P2 e P3.
--
--   P1  A ausência não é publicada por nenhuma fonte — `votacoesVotos` lista
--       só quem votou. AUSENTE e LICENCIADO passam a ser DERIVADOS do
--       cruzamento com mandato_exercicio.
--   P2  Quem não é candidato em 2026 é caso esperado, não alerta.
--   P3  A curadoria manual ganha autor e data; a correção retroativa de voto
--       e de ementa passa a ser registrada (internamente, não publicada).
--
-- Os valores de enum vieram na V2 — o Postgres não permite usá-los na mesma
-- transação em que são criados.
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS btree_gist; -- EXCLUDE de períodos por parlamentar

-- FONTE: a Casa publicou a linha. DERIVADO: nós a calculamos.
CREATE TYPE origem_registro_enum AS ENUM ('FONTE', 'DERIVADO');

CREATE TYPE situacao_exercicio_enum AS ENUM (
    'EXERCICIO', 'LICENCA', 'SUPLENCIA', 'CONVOCADO', 'FIM_MANDATO', 'VACANCIA'
);

CREATE TYPE condicao_eleitoral_enum AS ENUM ('TITULAR', 'SUPLENTE');

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
-- VotoNominal: distinguir o voto declarado do voto calculado por nós
-- ----------------------------------------------------------------------------
-- voto_origem deixa de ser NOT NULL porque linha derivada não tem rótulo de
-- origem a citar — e o CHECK garante que essa é a ÚNICA exceção. Note que
-- AUSENTE/LICENCIADO não ficam proibidos em linha de FONTE: se o Senado
-- publicar a ausência com rótulo próprio, ela entra como fato.

ALTER TABLE voto_nominal
    ALTER COLUMN voto_origem DROP NOT NULL,
    ADD COLUMN origem_registro origem_registro_enum NOT NULL DEFAULT 'FONTE',
    ADD CONSTRAINT voto_origem_coerente CHECK (
        (origem_registro = 'FONTE'    AND voto_origem IS NOT NULL)
     OR (origem_registro = 'DERIVADO' AND voto_origem IS NULL
                                      AND voto IN ('AUSENTE', 'LICENCIADO'))
    );

-- ----------------------------------------------------------------------------
-- IdentificadorExterno: curadoria manual deixa de poder ser anônima
-- ----------------------------------------------------------------------------
ALTER TABLE identificador_externo
    ADD COLUMN revisado_por TEXT,
    ADD COLUMN revisado_em  TIMESTAMPTZ,
    ADD CONSTRAINT revisao_auditavel CHECK (
        (revisado_manualmente = false AND revisado_por IS NULL
                                      AND revisado_em IS NULL)
     OR (revisado_manualmente = true  AND revisado_por IS NOT NULL
                                      AND revisado_em IS NOT NULL)
    );

-- ----------------------------------------------------------------------------
-- Quarentena: reprocessar não pode multiplicar o mesmo caso aberto
-- ----------------------------------------------------------------------------
-- 'FORA_DA_COORTE' é gravado uma vez por parlamentar; sem esta restrição
-- viraria uma linha por parlamentar POR EXECUÇÃO. NULLS NOT DISTINCT porque
-- id_externo é nulável (mesma armadilha do A4).
CREATE UNIQUE INDEX idx_rejeitado_unico_aberto
    ON staging.registro_rejeitado (fonte, recurso, id_externo, motivo)
    NULLS NOT DISTINCT
    WHERE resolvido_em IS NULL;

-- ----------------------------------------------------------------------------
-- Cobertura: o histórico de situação é a fonte que torna a derivação possível
-- ----------------------------------------------------------------------------
INSERT INTO cobertura_fonte (esfera, uf, recurso, status, fonte, disponivel_desde, observacao) VALUES
    ('FEDERAL', NULL, 'exercicio_parlamentar', 'DISPONIVEL', 'CAMARA', '2001-01-01', 'Histórico de situação (exercício, licença, suplência) por parlamentar. É o que permite derivar ausência e licença, que a fonte não publica como voto. O histórico alcança legislaturas anteriores, mas só é usado a partir de 2001, onde há voto nominal para cruzar.');
