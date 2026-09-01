-- ============================================================================
-- V5 — schema `dados_abertos`: o recorte publicável do banco curado.
--
-- Origem: recomendação R8 de docs/REVISAO_ARQUITETURA.md, aprovada em
-- 31/08/2026. A plataforma pede confiança justamente na parte que ninguém
-- consegue conferir de fora — o cruzamento entre TSE e as Casas. Publicar o
-- dado curado é o que permite refazer o cruzamento e apontar nosso erro.
--
-- São VIEWS, e não um script de export com colunas listadas à mão, para que a
-- exclusão de dado pessoal seja verificável por invariante (T48).
-- ============================================================================

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
           ambito, aprovada, placar_sim, placar_nao, placar_abstencao, url_fonte
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
    SELECT esfera, uf, recurso, status, fonte, disponivel_desde, observacao
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
