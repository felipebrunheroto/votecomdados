# Plano de implementação — os três itens em aberto

Fecha o que a [revisão de arquitetura](REVISAO_ARQUITETURA.md#-encerramento-da-revisão--31082026)
deixou pendente e destrava a implementação.

> **Restrição de calendário, dita de frente:** a eleição é em **04/10/2026** e
> hoje é 31/08/2026. São cinco semanas, com um desenvolvedor que também é o
> curador e o operador. O plano é ordenado por essa restrição — a seção
> [§ 5](#5-caminho-crítico-e-o-que-cabe-em-cinco-semanas) diz o que cabe e o
> que não cabe, em vez de listar tudo como se coubesse.

---

## 1. A12 — o Senado

### O que já foi verificado (31/08/2026)

O spike foi parcialmente executado ao escrever este plano, porque um plano
apoiado em premissa sobre o Senado repetiria o erro que a revisão inteira
combateu. **Três achados mudam o desenho:**

**1. O endpoint que os documentos presumiam está morto.**
`/dadosabertos/plenario/lista/votacao/{data}` responde 200 com lista vazia e
declara a própria descontinuação:

```json
"Descontinuacao": {
  "DataDepreciacao": "2025-03-18",
  "DataDesativacaoCompleta": "2026-02-01",
  "UrlServicoSubstituto": "https://legis.senado.leg.br/dadosabertos/votacao"
}
```

Desativado desde fevereiro. Qualquer estimativa baseada nele era ficção.

**2. O serviço substituto é melhor do que se supunha — e melhor que o da
Câmara.** `GET /dadosabertos/votacao` devolveu, numa chamada só, **96 votações
de 2025-09-02 a 2026-08-12**, cada uma com o campo `votos` contendo **81
registros — a bancada inteira**, não só quem votou.

A consequência é grande: **no Senado não é preciso derivar ausência.** Todo o
mecanismo do B8 (`mandato_exercicio` + derivação) existe porque a Câmara
publica só quem votou. O Senado publica o universo. O mesmo dado, dois
contratos opostos.

**3. O vocabulário do Senado não cabe no `tipo_voto_enum` atual.** Treze
rótulos distintos na amostra, e vários são categorias que hoje não existem:

| Sigla | Descrição | Ocorrências | Problema |
|---|---|---:|---|
| `Votou` | (sem descrição) | 2.621 | **Votação secreta**: participou, conteúdo não publicado |
| `Sim` / `Não` / `Abstenção` | — | 3.011 | Mapeiam direto |
| `P-NRV` | Presente – Não registrou voto | 1.133 | Presente e não votou ≠ ausente |
| `AP` | Atividade parlamentar | 609 | Ausência justificada por trabalho da Casa |
| `LS` / `LP` / `LAP` | Licença saúde / particular / paternidade | 185 | `LICENCIADO` cobre |
| `MIS` | Missão da Casa no País/exterior | 106 | Ausência justificada |
| `NCom` | Não Compareceu | 56 | A ausência de fato |
| `Presidente (art. 51 RISF)` | — | 48 | Equivalente ao Art. 17 da Câmara |
| `NA` | Dispositivo não citado | 7 | Não é voto |

O rótulo **mais frequente é `Votou`** — voto secreto. Mapeá-lo para `SIM` ou
`NAO` seria inventar; mapeá-lo para `AUSENTE` seria caluniar por omissão. É
exatamente o modo de falha do B5, numa fonte que ainda não integramos.

### ✅ Spike concluído em 31/08/2026 — as cinco respostas

A API publica **OpenAPI** em `/dadosabertos/v3/api-docs` (157 caminhos), então
os parâmetros vieram da especificação, não de tentativa e erro.

| # | Pergunta | Resposta |
|---|---|---|
| S1 | Profundidade histórica | **1991.** Nada em 1988/1990; 23 votações em 1991, 72 em 2001, 128 em 2025 — a bancada inteira em cada uma |
| S2 | Paginação / filtro | `dataInicio`/`dataFim` em **AAAA-MM-DD**, com **janela máxima de 1 ano** por chamada (o serviço recusa períodos maiores) |
| S3 | Matérias e autoria | `/dadosabertos/processo`, com filtro `codigoParlamentarAutor` — mas `autoria` vem como **texto livre** |
| S4 | `votacaoSecreta` confiável? | **Sim, perfeitamente.** Em 22 mil linhas, `Votou` ocorre **só** em `votacaoSecreta='S'`, e `Sim`/`Não`/`Abstenção` **só** em `'N'` |
| S5 | Arquivo em massa | Não há, mas a API aceita `Accept: text/csv` — **e é uma armadilha** (ver abaixo) |

**S1 tem uma anomalia a conferir:** 2015 e 2016 trazem 17 e 13 votações, contra
~130 nos anos vizinhos. Pode ser característica do período ou lacuna da fonte;
não investiguei a causa, e não se deve afirmar nenhuma das duas.

**S2 tem uma pegadinha de nomenclatura:** o parâmetro `ano` é o *ano da
proposição*, não o da sessão. Fatiar o backfill por ele produziria buracos —
tem de ser por `dataInicio`/`dataFim`. A janela de um ano casa exatamente com
o job já fatiado por `(fonte, ano)`.

**S3 tem consequência de produto:** sem autoria estruturada, a ligação
matéria→senador é feita **consultando por autor** (uma chamada por parlamentar
da coorte), e o campo `autoria` entra como `proposicao_autor.autor_nome` — que
o schema já suporta, com `politico_id` opcional.

**S5 é o achado mais perigoso do spike.** `Accept: text/csv` responde **HTTP 200
com o array `votos` silenciosamente descartado**: 128 linhas para 128 votações,
nenhuma coluna de voto. Quem seguisse a preferência do R1 por carga em massa
importaria o Senado inteiro **sem um único voto individual**, e o resultado
pareceria correto. **Para o Senado, JSON é requisito, não preferência** —
registrado em [`db/golden/README.md`](../db/golden/README.md).

### Volumetria do backfill do Senado

~35 anos × 1 chamada por ano ≈ **35 requisições** para todo o histórico de
votos nominais, mais uma por parlamentar da coorte para autoria. Está mais
perto do custo de um arquivo em massa do que do N+1 que o B3 eliminou.

### ✅ Decisões do owner em 31/08/2026, e o que já foi aplicado

**O Senado entra no MVP**, e o defeito de `cobertura_fonte` foi corrigido. O
que isso já significa no repositório:

| Aplicado | Onde |
|---|---|
| `SECRETO`, `PRESENTE_NAO_VOTOU`, `AUSENCIA_JUSTIFICADA` no enum de voto | migration `V6` |
| `votacao.secreta`; `cobertura_fonte.casa` + rechaveamento; `casa_do_mandato()`; 4 linhas de cobertura do Senado; 12 traduções de voto | migration `V7` |
| Resolução de cobertura por Casa (projeção **e** consulta normalizada) | `reconstruir_perfil_leitura()`, `PoliticoRepositorio` |
| `casa` e `secreta` no contrato, no Java e no TypeScript | `API.md`, `Modelo.java`, `tipos.ts` |
| 8 invariantes novos (T51–T58) | `db/test_invariantes.sql` |
| Amostra dos 13 rótulos, em JSON Lines | `db/golden/senado-votacao-amostra.jsonl` |

Sobra do W11 apenas o **código de ingestão** do Senado — o schema, o contrato e
as traduções já estão de pé e verificados.

### ⚠️ Achado estrutural: `cobertura_fonte` não sabia representar duas Casas

O spike expôs um defeito que **não estava previsto neste plano nem na revisão**.

`cobertura_fonte` é chaveada por `UNIQUE (esfera, uf, recurso)`, e a linha
`FEDERAL / voto_nominal` **já está ocupada pela Câmara, desde 2001**. Não há
como acrescentar a do Senado: o modelo assume uma fonte por
(esfera, UF, recurso), e a esfera federal tem **duas Casas com coberturas
diferentes**.

A consequência é que a plataforma hoje **informa errado sobre senadores**, nos
dois sentidos:

- diz que voto nominal federal existe "desde 2001", quando **o do Senado existe
  desde 1991** — dez anos a mais;
- e o diria mesmo enquanto o conector do Senado não existisse, sugerindo
  cobertura que não temos.

Isso não é detalhe de modelagem: `cobertura_fonte` é a tabela que sustenta a
promessa de neutralidade — distinguir *"a fonte não publica"* de *"ainda não
cobrimos"*. Ela erra justamente onde a assimetria é maior.

**Correção proposta:** acrescentar `casa` à chave, tornando-a
`UNIQUE NULLS NOT DISTINCT (esfera, uf, casa, recurso)`, com `casa` nula
significando "vale para a esfera inteira" (é o caso de `candidatura`, que vem
do TSE). A resolução por precedência ganha um nível, igual ao que já existe
para UF.

Isso alcança: o schema, a função `reconstruir_perfil_leitura()`, a consulta
`coberturaRelevante` da API, o contrato de `cobertura` em `API.md`, o tipo no
frontend e as fixtures. **✅ Aplicado em 31/08/2026**, com a decisão do owner. A `casa` entrou como
**partição**, não como precedência — a distinção importa: UF resolve para uma
resposta (SP ganha do fallback), enquanto Casa multiplica as respostas (quem foi
deputado e senador recebe as duas coberturas). O particionamento é pelo contexto
da pessoa, não pela linha, que é o que faz os dois eixos conviverem.
Verificado por T54–T57, inclusive o caso oposto: quem nunca foi senador não vê
cobertura do Senado.

### Consequências de schema, se o Senado entrar

Nada disso é opcional; é o preço de integrar a fonte sem mentir:

1. **Novos valores em `tipo_voto_enum`** (migration própria, pelo motivo da V2):
   `SECRETO` (participou, conteúdo não publicado), `PRESENTE_NAO_VOTOU`,
   `AUSENCIA_JUSTIFICADA`. Migration `V6`. O spike confirmou que os três são
   necessários: juntos são **12,3 mil das 22 mil linhas** da amostra — não é
   cauda, é o corpo da distribuição.
2. **Seed de `mapeamento_voto` para `SENADO`** com os 13 rótulos e nota
   metodológica em cada um — é a parte editorial, e vai para os dados abertos.
3. **`votacao.secreta BOOLEAN`**, para a UI dizer "votação secreta: a Casa
   registra a participação, não a escolha".
4. **Linhas em `cobertura_fonte` para o Senado** — hoje não existe nenhuma, e
   por isso o perfil de um senador não diria nada sobre cobertura.
5. **Golden file** — ✅ já em [`db/golden/senado-votacao-amostra.json`](../db/golden/senado-votacao-amostra.json):
   4 votações que cobrem os 13 rótulos, incluindo uma secreta. O invariante
   equivalente ao T40 entra com o conector, porque hoje só afirmaria que o que
   não existe não existe.
6. **A derivação do B8 fica condicionada à casa**: `CAMARA` deriva, `SENADO`
   não. O `origem_registro` já distingue as duas coisas no dado.

---

## 2. O worker de ingestão

Módulo `votecomdados-ingestion` — não existe ainda. Schema, contrato e frontend
estão prontos; é o que falta para a plataforma ter dado de verdade.

### Ordem, e por que ela é essa

A ordem de ingestão já está fixada em
[ARQUITETURA.md § 5](ARQUITETURA.md#quarentena-falhar-visível-em-vez-de-descartar-em-silêncio)
e existe para minimizar quarentena por construção:

```
COORTE (TSE)  →  cadastro de parlamentares  →  histórico de situação
      →  proposições e votações  →  votos e autoria  →  DERIVAÇÃO
      →  reconstruir_perfil_leitura()  →  exportar dados abertos
```

Inverter qualquer passo produz quarentena em massa: voto antes de político é
FK violada; derivação antes de `mandato_exercicio` marca ausência onde havia
licença.

### Fatias, na ordem de execução

| # | Fatia | Entrega | Depende de |
|---|---|---|---|
| W1 | ✅ **Feito** — módulo + `ControleDeExecucaoService` | Advisory lock por fonte, reaper de órfãs, watermark com `GREATEST`, `SET LOCAL votecomdados.execucao_id` — 10 testes de integração | — |
| W2 | ✅ **Feito** — staging com redação por allowlist + quarentena | `payload_bruto` e `registro_rejeitado` gravados; nenhum CPF em disco ou log — 12 testes | W1 |
| W3 | ✅ **Feito** — `JobDeCoorte` | `politico` + `candidatura`, poda, expurgo do `cpf_hmac` e leitura do zip real do TSE — 16 testes, 6 deles contra o arquivo de verdade | W2 |
| W4 | ✅ **Feito** — cadastro da Câmara + resolução de identidade | `identificador_externo` por nome civil + nascimento; fuzzy só na cauda; `FORA_DA_COORTE` uma vez por parlamentar — 10 testes sobre o CSV real | W3 |
| W5 | ✅ **Feito** — `mandato_exercicio` via `/deputados/{id}/historico` | Eventos → períodos sem sobreposição; só a coorte, não os 7,9 mil — 12 testes sobre o histórico real | W4 |
| W6 | ✅ **Feito** — backfill da Câmara por `COPY` | Votações, votos, proposições, temas e autoria dos arquivos reais — 20 testes | W4 |
| W7 | ✅ **Feito — o B8 fechado** | Universo do dia menos quem votou → `AUSENTE`/`LICENCIADO` com `origem_registro='DERIVADO'` — 12 testes, incluindo ponta a ponta com o arquivo real | W5, W6 |
| W8 | ✅ **Feito — A5 fechado** | O upsert corrige a ementa, e o gatilho guarda o que estava lá antes | W6 |
| W9 | ✅ **Feito** — projeção de leitura + dados abertos | Projeção reconstruída e pacote datado publicado ao fim de cada execução — 8 testes | W7 |
| W10 | ✅ **Feito** — `JobIncremental` diário | Delta pelo `If-Modified-Since` da própria fonte, não por REST — 11 testes | W6 |
| W11 | ✅ **Feito** — Senado | Votações e votos, com voto secreto tratado como categoria própria — 11 testes sobre a amostra real | spike |
| W12 | ✅ **Feito** — Alesp | Proposituras, autoria e votos de comissão, mapeados pelo código da fonte — 19 testes sobre as amostras reais | W6 |

**W1–W9 é o mínimo para a plataforma existir.** W10 é o que a mantém viva
depois; W11 e W12 são cobertura adicional.

### Como cada fatia se prova

A regra do projeto vale aqui — garantia sem execução é prosa:

- **Golden file por fonte**, como o `db/golden/` já faz para a Câmara. Fatia que
  introduz fonte nova entra com amostra real e invariante de mapeamento.
- **Teste de integração com Testcontainers** por job, exercitando o caminho
  completo contra o schema real.
- **Reprocessar é o teste mais importante**: rodar a mesma fatia duas vezes deve
  dar o mesmo banco. É o que a idempotência promete e ninguém verifica.
- **Quarentena esperada é zero** (fora `FORA_DA_COORTE`). Fatia que a deixa
  diferente de zero não está pronta.

### W1: o que a implementação decidiu

**O advisory lock é de sessão, e o job atravessa muitas transações.** Pegá-lo
por conexão de pool não funcionaria — a conexão volta ao pool no fim da
primeira transação e o lock some junto. O serviço passou a segurar uma conexão
própria enquanto o job vive.

O custo é uma conexão; o ganho é a propriedade que torna o reaper correto:
**se conseguimos o lock, nenhum processo vivo está nesta fonte.** Uma sessão
que morre libera o lock na hora e deixa a linha `EM_ANDAMENTO` para trás — e é
segurar o lock que autoriza limpar essa linha sem risco de matar um job vivo.

Por isso **o reaper não tem timeout**, e a ausência é deliberada: timeout seria
adivinhação, e adivinhar para baixo mata backfill lento, adivinhar para cima
trava a fonte por horas. A documentação original previa timeout; a execução
mostrou que ele é desnecessário e pior.

Dois detalhes que só apareceram rodando:

1. **`SET LOCAL`, nunca `SET`.** Valor de sessão vazaria para o próximo a usar
   a conexão do pool, e uma alteração passaria a ser atribuída a uma execução
   que não a fez. Coberto por dois testes — dentro da execução a alteração é
   atribuída a ela; fora, fica marcada como manual.
2. **O código de saída é contrato.** Um worker que termina em zero depois de
   quebrar faz o scheduler considerá-lo bem-sucedido, e o erro vira silêncio.
   Saída 1 para falha, 2 para "já havia execução viva" — que não é nem sucesso
   nem falha.

### W2: duas linhas de defesa, em ordem

**A allowlist é a garantia.** `RedatorDeCamposSensiveis` declara, por
(fonte, recurso), o que passa — e descarta o resto. Uma denylist ("apague o
CPF") protegeria contra o campo que já conhecemos; a allowlist protege contra o
que ainda não conhecemos. Fontes de governo acrescentam campo sem avisar, e com
allowlist o campo novo entra como **ignorado**, não como vazamento. Há teste
para exatamente isso.

Origem sem allowlist declarada **falha** em vez de gravar. É a escolha certa
para uma camada de segurança: recusar o desconhecido, não deixá-lo passar.

**A máscara de log é rede, não garantia.** Cobre o caminho que a allowlist não
alcança — o worker logando payload cru ao tratar erro, que é o vazamento mais
fácil de cometer porque só acontece quando algo já deu errado, e ninguém está
olhando. Sendo por padrão, é aproximada; confiar nela como mecanismo primário
seria trocar garantia estrutural por heurística.

Um teste loga um CPF por SLF4J e inspeciona a **saída real**: prova que a
máscara está no appender, não só que a regex funciona. A diferença importa —
uma classe correta desligada do pipeline não protege ninguém.

### ✅ W3 destravado: o arquivo do TSE chegou, e desmentiu duas suposições

O owner baixou `consulta_cand_2026.zip` de uma máquina que o TSE não bloqueia.
A verificação valeu cada minuto — **os dois erros encontrados apareceriam na
tela do eleitor**, e nenhum quebraria nada de forma visível:

**1. Todo candidato seria marcado como derrotado.** Os sentinelas do TSE são
`#NE` e `#NULO`, **sem** o `#` final que o código presumia. Com a grafia errada
o sentinela passava direto, e `DS_SIT_TOT_TURNO` virava "não eleito" — para uma
eleição que só ocorre em outubro. Seriam 20.809 candidatos publicados como
derrotados antes da votação.

**2. Todo candidato seria declarado apto.** `DS_SITUACAO_CANDIDATURA` vem `#NE`
(não especificado) em **100% dos registros** — o registro está sendo julgado
pela Justiça Eleitoral. A única tradução que o enum permitia era `APTO`, e a
plataforma afirmaria em nome do TSE algo que ele não disse. Corrigido com
`NAO_INFORMADO` (migration `V8`).

Outras quatro descobertas, todas capazes de quebrar a carga:

- a codificação é **latin-1**; ler como UTF-8 estoura no primeiro `Ç`;
- `_BRASIL.csv` é a **união exata** dos arquivos por UF — processar os dois
  dobraria o trabalho sem acrescentar uma linha;
- o zip traz um **`leiame.pdf`** junto dos CSVs;
- a allowlist citava **duas colunas que não existem** (`DS_DETALHE_SITUACAO_CAND`,
  `NM_MUNICIPIO_NASCIMENTO`) — campo fantasma dá impressão de estar sendo
  tratado, e nunca é. Há teste novo que confere a allowlist contra o cabeçalho
  real.

E uma que a allowlist barrou sozinha: o arquivo tem **`DS_EMAIL`**, contato
pessoal de cada candidato. Ninguém tinha pensado nele — é exatamente o
argumento da allowlist contra a denylist.

> ⚠️ **O zip está em `docs/consulta_cand_2026.zip` e contém o CPF de 20.809
> pessoas.** Ele não deve ficar no repositório: a plataforma inteira se apoia na
> premissa de que esse dado não é persistido. A amostra em `db/golden/` já tem o
> que os testes precisam, com os identificadores substituídos.

### W3: o problema que só apareceu ao juntar as peças

O expurgo do `cpf_hmac` (decisão Q11) e a reexecução do job se contradiziam, e
a contradição era silenciosa.

Se a identidade fosse resolvida pelo HMAC, funcionaria na primeira execução —
e a partir da segunda, com a coluna já zerada, o job deixaria de reconhecer as
pessoas gravadas. O resultado não seria erro: seriam **pessoas duplicadas e
trajetórias fragmentadas**, aparecendo como perfis incompletos que ninguém
saberia explicar.

A resolução passou a ter três níveis, nesta ordem:

1. **`sq_candidato_tse`** de uma candidatura já gravada — âncora *entre*
   execuções, sobrevive ao expurgo;
2. **`cpf_hmac`** — âncora *dentro* de uma execução, costura os anos;
3. **nome civil + data de nascimento** — último recurso, quando não há CPF.

Coberto por `reexecutar_depois_do_expurgo_nao_duplica_ninguem`.

Um segundo teste vale citar: o HMAC calculado em Java tem de bater com o do
`pgcrypto`. Se divergirem, o casamento simplesmente para de funcionar, sem
quebrar nada alto — exatamente o tipo de falha que este projeto trata como a
pior.

### W4: quatro desfechos, e a diferença entre dois deles é o produto

A resolução não devolve "achou / não achou". Devolve quatro coisas, porque
tratá-las igual é o erro caro:

| Desfecho | O que significa | O que acontece |
|---|---|---|
| `RESOLVIDO` | Nome civil + nascimento bateram | Vínculo vale |
| `PENDENTE_DE_CURADORIA` | Casou por similaridade | Gravado, **não conta como confirmado** |
| `AMBIGUO` | Mais de um plausível | Ninguém é escolhido; vai para o curador |
| `FORA_DA_COORTE` | Não é candidato em 2026 | **Esperado**; contado e nunca alertado |

**Empate nunca é desempatado por heurística.** Dois homônimos com o mesmo
nascimento viram ambiguidade — escolher um seria apostar com o histórico de
votação de alguém, e é o erro clássico deste domínio. Há teste para isso.

E a última linha é o que faz a métrica de quarentena servir: sem separar
`FORA_DA_COORTE`, a fila que exige ação humana nasceria com a maioria dos 7.889
parlamentares históricos dentro, e ninguém olharia para ela nunca mais.

O teste lê o CSV **verbatim** da Câmara, não uma fixture reescrita — inclusive
um que afirma que a coluna `cpf` continua vazia. Se um dia vier preenchida, ele
falha e avisa que a resolução pode melhorar.

### W5: a fonte dá eventos, o schema quer períodos

A conversão parece trivial e não é. O histórico da Câmara é uma lista de
**mudanças de status**, e virar períodos contíguos esbarra em três armadilhas —
todas encontradas no dado real, todas capazes de produzir erro silencioso na
derivação de ausência:

1. **Eventos sem `situacao`** ("Nome no início da legislatura", "Alteração de
   partido") são metadados. Tratá-los como transição criaria períodos fantasma
   e faria a mesma pessoa "entrar em exercício" várias vezes no mesmo mandato.
2. **A mesma `dataHora` se repete** — seis eventos em `2023-02-01T00:00` no
   deputado da amostra. Sem ordenação estável, o resultado mudaria entre
   execuções e o reprocesso deixaria de ser idempotente. Há teste exigindo que
   duas construções deem exatamente o mesmo resultado.
3. **Transições no mesmo dia** (convocação 14:56, posse 15:15). Como o período
   é em DATA, isso geraria intervalo vazio. Regra adotada: **vale a última
   situação do dia** — convocado e empossado no mesmo dia significa em
   exercício naquele dia.

**Correção de escopo em relação ao plano:** eram previstas ~7,9 mil chamadas,
uma por deputado cadastrado. São menos: ausência só é derivada para quem a
plataforma exibe, e a plataforma exibe a coorte. Buscar histórico de quem não é
candidato em 2026 gastaria a fonte para produzir linhas que ninguém veria — e
criaria dado pessoal fora do escopo declarado.

E a gravação **substitui** os períodos em vez de fazer upsert: a fonte corrige
histórico retroativamente, e um período que deixou de existir precisa sumir
daqui também. Upsert deixaria o antigo para trás, sobrepondo o novo — violando
o `EXCLUDE` ou, pior, produzindo ausência onde havia licença.

### W6: três erros que o arquivo real evitou

O mecanismo é o do R1 — baixa, `COPY` para tabela temporária de sessão,
transforma em SQL. O que valeu a pena foi ler o arquivo antes de mapear:

1. **`votosOutros` não é abstenção.** Numa linha real o campo vale 21 enquanto
   a própria descrição da votação diz "Abstenção: 3" — ele agrupa abstenção,
   obstrução e Art. 17. Mapeá-lo para `placar_abstencao` publicaria um número
   errado com aparência de certo. O campo fica nulo: a fonte não publica
   abstenção separada.
2. **`idProposicao = '0'` é sentinela de ausência**, não um id. Tratado como id,
   toda votação apontaria para uma proposição inexistente e a FK derrubaria a
   carga inteira.
3. **O horário é de Brasília.** Gravado como UTC, uma votação das 23:19 de 17/03
   viraria 18/03 — o A11 acontecendo na ingestão, onde é bem mais difícil de
   perceber que na UI. Há teste fixando o horário local.

E uma decisão que fecha o Q3 na prática: **quem está fora da coorte não vira
quarentena por voto.** A votação da amostra tem 376 votos e quase todos são de
parlamentares sem candidatura em 2026 — registrá-los aqui encheria a métrica de
alerta com dezenas de milhares de linhas por execução. Eles já foram contados
uma vez, no cadastro.

A tabela temporária é `ON COMMIT DROP` numa conexão dedicada: nada do arquivo
bruto sobrevive ao job, e `COPY`, transformação e `SET LOCAL` da execução
enxergam a mesma tabela e falham juntos.

### W6b + W8: o `tema` finalmente tem fonte, e a ementa deixa de congelar

Os três arquivos de proposições entram **juntos, numa transação só**, e não é
preferência: só se sabe quais matérias interessam depois de saber quem as
assinou, e o tema chega num terceiro arquivo. Ou os três entram, ou nenhum.

Três coisas que o arquivo real ensinou:

1. **`proposicoesTemas` não traz o id da proposição** — só a URI. O id sai dela
   por `regexp_replace`. É o tipo de detalhe que só aparece abrindo o arquivo, e
   que teria quebrado a carga inteira com "coluna não existe".
2. **`idDeputadoAutor` vem vazio** quando o autor é senador, órgão ou o
   Executivo. E — verificado em 2026 — **nenhuma matéria mistura** autor
   deputado com não-deputado. Então, na prática, o coautor sem perfil é um
   *deputado que não se recandidatou*, não um senador. Foi essa descoberta que
   corrigiu o teste: a primeira versão testava um cenário que a fonte não
   produz.
3. **Tema é substituído, não somado.** Tema retirado na origem precisa sumir
   daqui; só somar deixaria a classificação antiga colada para sempre — mesma
   regra dos períodos de exercício.

**O A5, fechado:** o upsert passou a corrigir `ementa`. A versão anterior
atualizava só situação e URL, então uma ementa corrigida pela Câmara nunca
chegaria à plataforma. E atualizar não virou perder: o gatilho registra a versão
anterior, com a execução que fez a mudança.

### W7: o teste ponta a ponta pegou um erro de classificação

A derivação em si saiu como desenhada. O que valeu foi o teste com o arquivo
real, que reprovou uma decisão tomada no W6 e que parecia óbvia.

**`tipo` (nominal ou simbólica) estava sendo decidido pelas linhas que
guardamos**, não pelo arquivo. Como só gravamos votos da coorte, uma votação em
que a Câmara votou nominalmente — mas nenhum candidato de 2026 participou —
ficava marcada como **simbólica**.

O efeito seria silencioso e grave: a derivação ignora simbólicas, então a
ausência de quem estava em exercício naquele dia simplesmente não existiria. O
nosso recorte de escopo teria virado uma **afirmação falsa sobre como a Câmara
votou**.

Corrigido: nominal ou simbólica é fato sobre o procedimento da Casa, e se
decide pelo arquivo. É a mesma família do B8 — deixar o que não coletamos
parecer o que não aconteceu.

### O que a derivação garante, e está testado

| Situação na data | Registrou voto? | Resultado |
|---|---|---|
| `EXERCICIO` | sim | o voto declarado, `FONTE` |
| `EXERCICIO` | não | `AUSENTE`, `DERIVADO`, sem rótulo de origem |
| `LICENCA` | não | `LICENCIADO` — nunca `AUSENTE` |
| `SUPLENCIA`, `FIM_MANDATO` | — | **nenhuma linha**: não é ausência, é não ser parlamentar |

Mais três propriedades que só teste pega:

- **Recalcula do zero**, não completa. Um período corrigido retroativamente
  pela fonte precisa apagar a ausência antiga — senão a plataforma segue
  dizendo que alguém faltou a uma sessão de que nunca participou.
- **Voto que chega atrasado sobrescreve a ausência calculada**, virando `FONTE`.
- **O Senado é recusado**, não silenciosamente ignorado: ele publica a bancada
  inteira, e derivar lá duplicaria o que a Casa já declara como fato.

### W9: uma implementação, não duas

O pacote de dados abertos já existia como script shell. Implementá-lo também em
Java — necessário para publicar ao fim da ingestão sem depender de `psql` no
container — criaria **duas representações do mesmo pacote**, que é exatamente a
classe de divergência que `validar-migrations.sh` e `validar-contrato.mjs`
existem para impedir.

O script foi removido. O recorte publicável continua sendo o schema
`dados_abertos` (views), que é o que o invariante T48 verifica; a implementação
só transporta. Se ela escolhesse colunas, viraria um segundo lugar onde o
recorte pode divergir — e aí a garantia de "nenhum dado pessoal sai" voltaria a
ser promessa em vez de teste.

Dois testes que valem citar:

- **O pacote não leva CPF nem a identificação do curador** — varrendo todos os
  arquivos gerados, não só o que se espera que os contenha. É o B1 valendo
  também na saída.
- **A metodologia declara a fila de curadoria pendente**, com o número
  desconfortável interpolado. Sem ele o pacote seria peça de marketing em vez
  de instrumento de auditoria.

### W10: o incremental não é REST, e a fonte é que decidiu isso

A arquitetura previa "arquivos em massa no backfill, REST no incremental". A
verificação desfez a distinção: os arquivos anuais da Câmara são **regravados
diariamente** e respondem **`304 Not Modified`** a `If-Modified-Since`
(verificado em 31/08/2026). O incremental virou o mesmo caminho do backfill,
restrito ao ano corrente.

O que se ganha:

- **Um caminho de código em vez de dois.** Nenhuma máquina de paginação, rate
  limit e circuit breaker — a mesma que o B3 apontou como sintoma de padrão de
  acesso equivocado.
- **O ciclo sobre dado estável custa zero byte.** É o que torna aceitável rodar
  todo dia até o fim da legislatura, como a resposta 6 pede.
- **O watermark passa a ser o `Last-Modified` da fonte**, e não um relógio nosso
  tentando adivinhar o dela.

O que se perde: frescor intradiário — que a resposta 6 dispensou
explicitamente. **Gatilho para reabrir:** a plataforma precisar exibir um voto
no mesmo dia.

Duas decisões menores que o teste cobre:

- **O grupo baixa junto ou não baixa.** Proposições, temas e autoria cruzam-se
  na carga; meio grupo atualizado produziria autoria apontando para matéria que
  não existe mais.
- **Sem mudança, o watermark fica onde estava.** Avançar o marcador sobre uma
  janela vazia é o mesmo que uma execução falha fingindo sucesso — o modo de
  falha do B6.

### O worker agora executa de verdade

`SeletorDeJob` deixou de ser esqueleto: `--job=COORTE --fonte=TSE --arquivo=…`
e `--job=INCREMENTAL --fonte=CAMARA` rodam ponta a ponta. A publicação dos
dados abertos é opcional (`--dados-abertos=…`) e **falha sem derrubar a
ingestão**: o dado já gravado vale, e o pacote sai no ciclo seguinte.

### W11: a fonte que se comporta ao contrário

O Senado publica **a bancada inteira em cada votação** — 81 registros, com
licença, missão e ausência declaradas pela própria Casa. A consequência é que
**nada ali é derivado**: o `DerivadorDeAusencia` recusa o Senado de propósito,
porque derivar duplicaria o que a fonte já afirma.

O que o carregador precisou tratar como diferença, e não achatar:

| Rótulo | Vira | Por que não pode virar outra coisa |
|---|---|---|
| `Votou` | `SECRETO` | Participou de votação secreta. `SIM`/`NAO` inventaria posição; `AUSENTE` caluniaria por omissão |
| `P-NRV` | `PRESENTE_NAO_VOTOU` | Estava na sessão. Contar como falta seria errado |
| `AP`, `MIS` | `AUSENCIA_JUSTIFICADA` | Trabalho da Casa não é falta nem licença |
| `LS`, `LP`, `LAP` | `LICENCIADO` | Três tipos de licença, uma categoria |
| `NCom` | `AUSENTE` | A ausência de fato |
| `NA` | **quarentena** | "Dispositivo não citado" não é voto, e não tem tradução honesta |

**Votação secreta continua `NOMINAL`**, e a distinção importa: há registro de
quem participou, só não de como votou. Marcá-la simbólica esconderia a
participação — e são 53% das votações de plenário do Senado.

### Riscos que já dá para nomear

1. **A resolução de identidade (W4) é o maior risco de produto, não o backfill.**
   Um vínculo errado atribui a alguém o voto de outra pessoa. O casamento
   determinístico por nome civil + nascimento cobre o caso geral (100%
   preenchido de 2011 em diante), mas a cauda vai para curadoria — e a fila
   precisa caber antes do lançamento.
2. **O backfill esgota crédito de CPU do banco burstable.** Rodar fora de
   horário ou subir a instância temporariamente, como o plano de custos prevê.
3. **W5 é o único N+1 que sobrou** (~7,9 mil chamadas). É aceitável por ser uma
   vez só, mas precisa de rate limit e retry — e de watermark próprio, para não
   recomeçar do zero se morrer no meio.

---

## 3. Link para os dados abertos no frontend

O menor dos três, e o mais alinhado à proposta: hoje o pacote é gerado e
publicado, e **nenhuma tela aponta para ele**.

| # | Passo | |
|---|---|---|
| F1 | Componente `Rodape` no layout, com link para `/dados-abertos/latest/` e para a metodologia | ✅ |
| F2 | Página `/dados-abertos` explicando o pacote, a licença CC BY 4.0 e os avisos do `LEIA-ME.md` — em linguagem de leitor | ✅ |
| F3 | Caminho de verificação no fim do perfil, junto do aviso de cobertura | ✅ |

**O que a execução acrescentou ao previsto:**

- Os avisos do `LEIA-ME.md` são **cinco**, não quatro — o quinto é que a
  tradução dos rótulos é editorial, e é justamente o que o W12 tornou mais
  visível.
- A volumetria da página é lida do `manifesto.json` **em tempo de execução**,
  não escrita à mão. Foi isso que permitiu oferecer o endereço **datado** de
  citação: o build não sabe qual é o instantâneo mais recente, o pacote sabe.
- **Defeito de fuso encontrado no caminho:** `manifesto.gerado_em` é data de
  calendário (`2026-09-01`), e `formatarDataLonga` a converteria para
  `America/Sao_Paulo`, exibindo **31 de agosto**. Numa página que declara "este
  pacote é de tal dia", errar a data é errar o endereço de citação. Entrou
  `formatarDataDoDia`, que formata em UTC porque o valor já é um dia, sem
  instante associado.
- **Risco de deploy documentado:** o site exporta `out/dados-abertos/index.html`
  e a ingestão publica `dados-abertos/AAAA-MM-DD/` no mesmo bucket. O
  `aws s3 sync --delete` do frontend apagaria todos os instantâneos datados —
  destruindo a promessa de endereço de citação imutável. O comando em
  `FRONTEND.md` passou a levar `--exclude 'dados-abertos/20*'`.

> **Antes de codificar:** `web/AGENTS.md` avisa que esta versão do Next.js tem
> mudanças de API em relação ao que se presume. Ler o guia em
> `node_modules/next/dist/docs/` antes de criar layout novo.

---

## 3b. O que ficou fora deste plano e ainda falta

As doze fatias e o link do frontend estão entregues, mas **uma execução real de
ponta a ponta ainda não produziria o site**: faltam três costuras entre peças
que já existem e já têm teste — a marcação de `possui_atuacao_legislativa`, a
orquestração do backfill e o cliente do Senado.

Elas têm plano próprio em
[PLANO_EXECUCAO_REAL.md](PLANO_EXECUCAO_REAL.md), com a fonte do Senado
verificada em 01/09/2026.

---

## 4. O que este plano deliberadamente não inclui

- **Tracing distribuído** (A9) — decidido não adotar, com gatilho registrado.
- **Keyset pagination** (A6) — risco aceito na volumetria atual.
- **Câmaras municipais e as outras 26 assembleias** — fora do MVP por decisão.
- **Interface de curadoria** — a curadoria é por SQL no piloto.
- **Multi-AZ e réplica de leitura** — single-AZ assumido com backup e restore.

---

## 5. Caminho crítico e o que cabe em cinco semanas

```
spike Senado (meio dia)  ──┐
                           ├─→ decisão: Senado entra no MVP ou vira "fora do escopo"
W1 → W2 → W3 → W4 → W5 ────┤
                    └→ W6 ─┴─→ W7 → W9 → plataforma com dado real
                        └→ W8
                                W10 (mantém viva)      F1 → F2 (torna auditável)
```

**Recomendei tratar a Câmara como escopo de lançamento e o Senado como bônus;
o owner decidiu incluir o Senado, e o trabalho de julgamento que eu temia foi
feito na hora:** os treze rótulos estão traduzidos em `mapeamento_voto`, com
nota metodológica em cada um que precisa, e o único sem tradução honesta
(`NA`) ficou declarado como tal — vai para quarentena, e um invariante fixa
esse conjunto.

O que sobra do Senado é código de ingestão, não decisão editorial. O risco que
sustentava minha recomendação foi eliminado antes da implementação começar, que
é exatamente onde ele deveria ter sido eliminado.

**O que não pode ser cortado**, sob nenhuma pressão de calendário: a derivação
de ausência (W7) e a fila de curadoria zerada (W4). Sem a primeira, a
plataforma omite faltas em silêncio; sem a segunda, ela pode atribuir a alguém
o voto de outra pessoa. São os dois modos de falha que destroem a premissa do
projeto — e nenhuma data de eleição justifica publicá-los.

### W12: o campo que a documentação não mencionava

O plano previa mapear **477 rótulos de voto em texto livre** e mandar a cauda
para quarentena. Bastou abrir o arquivo para ver que a premissa estava errada,
e para melhor: a Alesp publica **dois** campos de voto, e o primeiro é um
**código** de 8 valores, documentado no PDF dela.

| Campo | O que é | Valores |
|---|---|---|
| `<TipoVoto>` | código — a classificação **da fonte** | 8 |
| `<Voto>` | "descrição do tipo do voto", segundo a doc da Alesp | 477 |

Mapear pelo texto deixaria ~1% dos votos em quarentena permanente **e
crescendo**, porque texto livre cresce. Mapear pelo código é usar a
classificação que a Casa já fez. Os dois são preservados —
`voto_origem_codigo` e `voto_origem`.

Dois códigos exigiram valor novo de enum, e a alternativa foi descartada por
número: `S` (voto em separado, 2.130) e `B` (branco, 186) em quarentena
alertariam **2.316 vezes por backfill**, quebrando a regra de que quarentena
esperada é zero — e treinando a ignorar o alerta.

#### O que só apareceu contra o dado real

| Achado | Número | Consequência |
|---|---|---|
| `F` e `P` podem ser **opostos** na mesma deliberação | 36 de 29.923 | A deliberação inteira vai para quarentena: a ambiguidade não é do código, é da votação |
| `<IdDeputado>` significa coisas diferentes em arquivos diferentes | 7 colisões em 94 | O vínculo usa `IdSPL`; pelo campo óbvio, Carlão Pignatari receberia os 101 mil registros de autoria de Enio Tatto |
| Reuniões referenciadas que não existem no arquivo de reuniões | 3 (361 votos) | Sem data não há votação: quarentena |
| `IdDocumento` repetido, byte a byte | 12.413 | Deduplicado na carga |
| `DtPublicacao` = `0001-01-01` | 6 | Vira nulo, não ano 1 |
| Documento votado ausente das proposituras | 2.118 de 18.973 | Votação sem matéria ligada, em vez de voto descartado |
| Datas de `cobertura_fonte` erradas nas **duas** linhas | 1995 → 2006-02-15 e 1970-09-23 | Erravam em sentidos opostos: uma afirmava 11 anos que não existem, a outra escondia 25 que existem |
| A Alesp **publica** voto nominal de plenário — em PDF com imagem | — | `NAO_PUBLICADO_PELA_FONTE` continua, mas a redação mudou: "não existe" e "não é legível por máquina" são afirmações diferentes |

#### O que a fatia não afirma

- **`aprovada` fica nula.** A fonte publica os votos, não o resultado. Contar
  e concluir seria apurar a votação em nome da Casa, e comissão tem quórum e
  desempate que o arquivo não expõe.
- **Nada é derivado.** A Alesp publica quem votou, não quem faltou, e não
  publica a composição da comissão por data. Ausência em comissão não é
  afirmada.
- **Nenhum vínculo da Alesp é determinístico.** O cadastro traz `Aniversario`
  como **dia/mês, sem o ano**, e não traz nome civil. Todos os vínculos saem
  como `FUZZY`, para o curador confirmar.

#### Dois defeitos do próprio projeto, achados de raspão

1. **`mvnd` montava só `backend/`**, enquanto os testes leem `db/golden/` por
   caminho relativo. Nenhum teste com golden file passava por esse caminho.
2. **`db/validar.sh` não distinguia "invariante passou" de "invariante não
   rodou".** Um `SELECT` sem linhas não imprime nem OK nem FALHOU. Foi o que
   aconteceu com o T20 ao trocar a chave de mapeamento da Alesp: ele sumiu em
   silêncio e o script seguiu verde. Agora os invariantes declarados são
   comparados com os emitidos.
