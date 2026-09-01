-- ============================================================================
-- V10 — a Alesp mapeada pelo CÓDIGO da fonte, e as datas de cobertura corrigidas.
--
-- O que o spike do W12 (31/08/2026) mudou, contra os arquivos reais:
--
-- 1. `comissoes_permanentes_votacoes.xml` tem DOIS campos de voto:
--      <TipoVoto>  código de uma letra — 8 valores, documentados no PDF da
--                  própria Alesp (F, C, S, O, P, T, A, B);
--      <Voto>      texto livre, que a documentação chama de "Descrição do tipo
--                  do voto" — 477 valores distintos, com erros de digitação
--                  ("proejto") e frases inteiras.
--
--    A documentação e o schema descreviam só o segundo, e o plano era mapear
--    477 strings e mandar a cauda para quarentena. Isso deixaria ~1% dos votos
--    permanentemente em quarentena — e crescendo, porque texto livre cresce.
--
--    Mapear pelo código é usar a classificação DA FONTE em vez de uma nossa.
--
-- 2. As datas de cobertura da Alesp estavam erradas nas duas linhas. Mesmo
--    tipo de defeito que o Senado revelou na V7: número plausível, nunca
--    conferido.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. O código da fonte, ao lado do texto da fonte
-- ----------------------------------------------------------------------------
-- Guardar só o código perderia o texto, que é o fato mais rico e é o que a UI
-- mostra em "registrado como". Guardar só o texto perderia a classificação da
-- fonte, que é o que torna o mapeamento estável. Os dois são fato; a coluna
-- separa o que a fonte AFIRMA (código) do que ela DESCREVE (texto).
--
-- NULL para Câmara e Senado, que publicam um campo só.
ALTER TABLE voto_nominal ADD COLUMN voto_origem_codigo TEXT;

COMMENT ON COLUMN voto_nominal.voto_origem_codigo IS
    'Código de voto da fonte, quando ela publica um separado do rótulo textual. '
    'Hoje só a Alesp (<TipoVoto>). É a chave de mapeamento_voto para essa fonte.';

-- Linha derivada não tem rótulo de origem a citar — nem texto, nem código.
ALTER TABLE voto_nominal ADD CONSTRAINT voto_origem_codigo_coerente CHECK (
    origem_registro = 'FONTE' OR voto_origem_codigo IS NULL
);

-- ----------------------------------------------------------------------------
-- 2. O vocabulário real da Alesp
-- ----------------------------------------------------------------------------
-- Fora as seis linhas de texto livre que o V1 semeou por suposição. Nenhuma
-- delas era chave estável: "Favorável ao parecer" é UM dos 141 textos que a
-- fonte emite para o código 'F'.
DELETE FROM mapeamento_voto WHERE fonte = 'ALESP';

-- Os 8 códigos, com a contagem verificada em 226.067 votos reais.
--
-- F e P são AMBOS favoráveis, e a distinção entre eles é sobre O QUE se vota:
-- F é favorável ao parecer do relator, P é favorável ao projeto. Na prática a
-- Casa usa um par OU o outro numa mesma deliberação — F/C ou P/T. Verificado:
-- C e T nunca coexistem numa deliberação (0 casos em 29.923).
--
-- Quando F e P coexistem (36 deliberações, 0,12%) eles podem ser OPOSTOS — o
-- texto "Favorável ao projeto e contrário ao parecer" vem codificado como P
-- ao lado de um F. Esses votos vão para quarentena, e é o job que decide:
-- a ambiguidade é da DELIBERAÇÃO, não do código, e por isso não cabe aqui.
INSERT INTO mapeamento_voto (fonte, valor_origem, voto, observacao) VALUES
    ('ALESP', 'F', 'SIM',       'Favorável ao parecer do relator, em comissão. Não é votação de plenário.'),
    ('ALESP', 'P', 'SIM',       'Favorável ao projeto, em comissão. Não é votação de plenário.'),
    ('ALESP', 'C', 'NAO',       'Contrário ao parecer do relator, em comissão. Não é votação de plenário.'),
    ('ALESP', 'T', 'NAO',       'Contrário ao projeto, em comissão. Não é votação de plenário.'),
    ('ALESP', 'A', 'ABSTENCAO', 'Abstenção em comissão.'),
    ('ALESP', 'B', 'BRANCO',    'Voto em branco. A Alesp o conta separado da abstenção.'),
    ('ALESP', 'S', 'VOTO_EM_SEPARADO', 'Voto em separado: o parlamentar votou apresentando parecer escrito divergente do relator. A fonte não diz se o divergente é favorável ou contrário ao projeto.');

-- DELIBERADAMENTE NÃO MAPEADO: o código 'O' ("Outros"), documentado pela Alesp
-- e ausente dos 226.067 votos atuais. É a própria fonte dizendo "não
-- classificado" — traduzi-lo seria inventar. Se aparecer, vai para quarentena,
-- que é a regra do projeto. O invariante T61 fixa esse conjunto.

-- ----------------------------------------------------------------------------
-- 3. Cobertura da Alesp: as datas que os arquivos realmente sustentam
-- ----------------------------------------------------------------------------
-- votacao_comissao dizia 1995 e o arquivo começa em 2006-02-15 — onze anos de
-- cobertura afirmada que não existe.
UPDATE cobertura_fonte
   SET disponivel_desde = DATE '2006-02-15',
       observacao = 'Votos individuais em comissões permanentes da Alesp, desde '
                 || 'fevereiro de 2006. A Casa publica o voto de cada membro da '
                 || 'comissão, e não a lista de quem faltou — ausência em comissão '
                 || 'não é derivada.'
 WHERE esfera = 'ESTADUAL' AND uf = 'SP' AND casa = 'ALESP'
   AND recurso = 'votacao_comissao';

-- proposicao dizia 1995 e o arquivo alcança 1970 — a data errava para o lado
-- oposto, escondendo cobertura que existe. Mas a série é rala antes de 1995
-- (497 registros em 25 anos, contra 278 mil no total), e a observação diz isso
-- em vez de deixar o leitor supor densidade uniforme.
UPDATE cobertura_fonte
   SET disponivel_desde = DATE '1970-09-23',
       observacao = 'Proposituras e autoria no portal de dados abertos da Alesp. '
                 || 'A série alcança 1970, mas é rala antes de 1995: 497 matérias '
                 || 'em 25 anos, contra 278 mil no total.'
 WHERE esfera = 'ESTADUAL' AND uf = 'SP' AND casa = 'ALESP'
   AND recurso = 'proposicao';

-- voto_nominal de plenário: o status continua NAO_PUBLICADO_PELA_FONTE, mas a
-- redação estava imprecisa de um jeito que importa. A Alesp PUBLICA votação
-- nominal de plenário — verificado em 31/08/2026 na API do portal
-- (/sessoes-plenarias/{id}/votacoes) —, só que o registro de quem votou como
-- vem dentro de um PDF por votação, com imagem embutida. Existe e não é
-- legível por máquina. Dizer "não publica" faria o eleitor concluir que o
-- registro não existe, que é afirmação diferente e falsa.
UPDATE cobertura_fonte
   SET observacao = 'A Alesp publica as votações nominais de plenário apenas como '
                 || 'PDF por votação, um por deliberação, sem dado estruturado de '
                 || 'quem votou como. O registro existe e não é legível por '
                 || 'máquina; a limitação é da forma de publicação da fonte, não '
                 || 'da plataforma.'
 WHERE esfera = 'ESTADUAL' AND uf = 'SP' AND casa = 'ALESP'
   AND recurso = 'voto_nominal';
