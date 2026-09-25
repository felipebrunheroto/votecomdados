-- Emendas parlamentares, da API de Dados do Portal da Transparência (CGU).
--
-- O modelo abaixo é consequência direta do spike de 25/09/2026 sobre o ano de
-- 2025 inteiro (6.311 emendas, R$ 38,6 bi desembolsados). Cada decisão aqui
-- existe porque uma medição desaconselhou o desenho óbvio. Ver
-- docs/DISCOVERY_EMENDAS.md.

ALTER TYPE fonte_enum ADD VALUE IF NOT EXISTS 'PORTAL_TRANSPARENCIA';

-- A localidade vem como TEXTO LIVRE, em quatro formas. Classificar na
-- ingestão, e não na leitura, é o que permite a página declarar a lacuna sem
-- reparsear string a cada requisição -- e a lacuna é enorme: por valor pago em
-- 2025, MULTIPLO 88,5%, ESTADO 7,5%, MUNICIPIO 3,4%, NACIONAL 0,6%.
--
-- MULTIPLO é várias localidades numa linha só, SEM discriminar quanto foi para
-- cada uma, e não é decomponível: /emendas/documentos/{codigo} devolve apenas
-- número de empenho, sem nenhum campo de localidade.
CREATE TYPE localidade_emenda_enum AS ENUM (
    'MUNICIPIO', 'ESTADO', 'NACIONAL', 'MULTIPLO', 'OUTRO'
);

CREATE TABLE emenda (
    -- "202541840004" = ano 2025 + autor 4184 + número 0004. Estável e único;
    -- confirmado contra numeroEmenda em 6.311 de 6.311 linhas.
    codigo              TEXT PRIMARY KEY,
    ano                 INT  NOT NULL,
    numero              TEXT NOT NULL,
    tipo                TEXT NOT NULL,

    -- O código do autor NÃO vem num campo próprio: `autor` e `nomeAutor`
    -- trazem os dois o mesmo nome puro. O código sai dos dígitos 5 a 8 de
    -- `codigo`. Em 2025 foram 628 códigos para 628 nomes, com ZERO
    -- ambiguidade nos dois sentidos -- por isso ele serve de âncora.
    codigo_autor        TEXT NOT NULL,
    autor_nome          TEXT NOT NULL,

    -- Autoria transferida. 1,7% das linhas (110 de 6.311, 14 parlamentares,
    -- R$ 473 mi) chegam como "FULANO (EX-PARLAMENTAR BELTRANO, NOS TERMOS
    -- ART. 78 LDO 2025 ...)". O padrão é regular: casou em 14 de 14.
    --
    -- Guardar os dois não é preciosismo. Atribuir a emenda só a quem consta
    -- hoje esconde metade da história; e o nome CRU, com a anotação, não casa
    -- com pessoa nenhuma da nossa base -- o parse é pré-requisito do vínculo.
    autor_origem_nome   TEXT,

    -- NULÁVEL de propósito. Nossa base é a coorte de 2026: parlamentar que não
    -- se candidatou este ano não existe nela. Medido: 8,3% dos autores
    -- individuais de 2025 estão nessa situação. Não é falha de vínculo a
    -- corrigir -- é emenda que existe sem perfil onde exibi-la, e o job não
    -- pode descartá-la por isso.
    politico_id         UUID REFERENCES politico(id) ON DELETE SET NULL,

    localidade_bruta    TEXT NOT NULL,
    localidade_tipo     localidade_emenda_enum NOT NULL,
    -- Só preenchidos quando localidade_tipo = 'MUNICIPIO'. Não há código IBGE
    -- na fonte, e não há no nosso schema: o cruzamento é por nome + UF até
    -- existir uma tabela de correspondência.
    municipio_nome      TEXT,
    uf                  CHAR(2),

    funcao              TEXT,
    subfuncao           TEXT,

    valor_empenhado     NUMERIC(15, 2),
    valor_liquidado     NUMERIC(15, 2),
    valor_pago          NUMERIC(15, 2),
    valor_resto_inscrito   NUMERIC(15, 2),
    valor_resto_cancelado  NUMERIC(15, 2),
    -- A coluna que impede a página de mentir. `valor_pago` sozinho conta
    -- 84,2% do desembolso: em 2025 saíram R$ 32,5 bi por ele e R$ 6,1 bi por
    -- restos a pagar. E 43 das 474 cidades com emenda identificada têm
    -- valor_pago = 0 com restos pagos > 0 -- Rondonópolis some com R$ 5
    -- milhões se a leitura olhar só "pago".
    valor_resto_pago       NUMERIC(15, 2),

    coletado_em         TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT municipio_so_quando_e_municipio CHECK (
        (localidade_tipo = 'MUNICIPIO' AND municipio_nome IS NOT NULL AND uf IS NOT NULL)
        OR (localidade_tipo <> 'MUNICIPIO' AND municipio_nome IS NULL AND uf IS NULL)
    )
);

-- Perfil do político: "as emendas desta pessoa", do ano mais recente primeiro.
CREATE INDEX idx_emenda_politico ON emenda (politico_id, ano DESC)
    WHERE politico_id IS NOT NULL;

-- Página do município: "o que esta cidade recebeu". Parcial porque só 12% das
-- linhas são de município -- indexar as outras 88% seria carregar peso morto.
CREATE INDEX idx_emenda_municipio ON emenda (uf, municipio_nome, ano DESC)
    WHERE localidade_tipo = 'MUNICIPIO';

-- Vínculo por código de autor, usado a cada ingestão para reaproveitar o
-- casamento já feito em vez de recasar nome por nome.
CREATE INDEX idx_emenda_codigo_autor ON emenda (codigo_autor);
