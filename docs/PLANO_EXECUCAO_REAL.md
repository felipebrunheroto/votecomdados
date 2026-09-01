# Plano — o que falta para o pipeline rodar de verdade

`docs/PLANO_IMPLEMENTACAO.md` fechou: W1–W12 entregues, frontend completo, 159
testes, 65 invariantes. Ainda assim **uma execução real de ponta a ponta não
produziria o site**. Três lacunas explicam isso, e este documento é o plano
para fechá-las.

Nenhuma delas é funcionalidade nova: são costuras entre peças que já existem e
já têm teste. É por isso que cabem num plano curto — e é por isso que ficaram
para trás, porque nenhum teste de fatia as exercita.

| | Lacuna | Sintoma se rodasse hoje |
|---|---|---|
| **A1** | `possui_atuacao_legislativa` nunca é escrito | Nenhum perfil pré-renderizado; todo candidato "sem atuação legislativa" | ✅ |
| **A2** | `BACKFILL` lança exceção no `SeletorDeJob` | Não há como carregar o histórico por linha de comando | ✅ |
| **A3** | Senado sem cliente e sem cadastro | `JobDoSenado` é código morto em produção | ✅ |

**Ordem: A1 → A3 → A2.** A1 é pequeno e desbloqueia o site inteiro; A3 é o que
tira o Senado do papel; A2 é orquestração de algo que já funciona.

**A4 (fotos de candidato) foi decidido fora deste plano: não vai acontecer.**
Decisão de produto de 01/09/2026 — a plataforma não terá fotos. A coluna
`foto_url` foi removida do schema e do contrato (V11), em vez de ficar nula
para sempre.

---

## O que a verificação da fonte mudou no plano

Antes de escrever qualquer linha, o Senado foi conferido contra a API real em
01/09/2026 — mesma disciplina do W11 e do W12. Dois achados alteram o desenho:

**1. A API do Senado não publica `Last-Modified` nem `ETag`.** Só
`cache-control: max-age=600`. O `BaixadorDeArquivos`, que torna o incremental
da Câmara e da Alesp quase gratuito, **não serve aqui**: não há a quem
perguntar "mudou?". O watermark do Senado precisa sair de outro lugar — ver A3.

**2. `/senador/{codigo}` publica `DataNascimento`** (verificado:
`1976-10-23` para o código 5672). Isso é decisivo e é o oposto da Alesp: o
Senado **permite casamento determinístico** por nome completo + nascimento,
como a Câmara. Nenhum vínculo do Senado precisa nascer `FUZZY`.

Também confirmado, e que entra como requisito:

| Endpoint | Serve para | Observação |
|---|---|---|
| `/senador/lista/atual` | 81 senadores em exercício | Traz `EmailParlamentar`, `Telefones` e `UrlFotoParlamentar` — **exige allowlist** |
| `/senador/lista/legislatura/{n}` | 245 nomes na 56ª | Cobre quem saiu e é candidato em 2026; a lista "atual" sozinha não basta |
| `/senador/{codigo}` | `DataNascimento`, nome completo | Uma chamada por parlamentar da coorte |
| `/votacao?ano=AAAA` | Votações + votos do ano | O parâmetro é `ano`; datas são `AAAA-MM-DD` (`20260701` responde **400**) |

---

## A1 — marcar quem tem atuação legislativa ✅ Entregue (01/09/2026)

### O problema

`politico.possui_atuacao_legislativa` é declarado `NOT NULL DEFAULT false` na
V1 e **nunca recebe `UPDATE`** em lugar nenhum do código. Quem depende dele:

- `PoliticoRepositorio.todosOsIdsComAtuacao()` → a lista de
  `generateStaticParams`, ou seja, **quais perfis existem como HTML**;
- o filtro `comAtuacao` de `GET /politicos`;
- `perfil_leitura`, que copia a coluna;
- `dados_abertos.politico`, que a publica.

Com ela sempre `false`, o build estático não gera perfil nenhum e o pacote de
dados abertos afirma que ninguém tem atuação. O comentário no `schema.sql` já
descreve o passo que falta: *"mantido pelo pipeline ao fim do backfill"*.

### A decisão editorial embutida

"Atuação legislativa" precisa de definição, e ela não é óbvia num ponto:

| Sinal | Conta? | Por quê |
|---|---|---|
| Autoria em `proposicao_autor` | ✅ | Apresentou matéria |
| Voto em `voto_nominal` com `origem_registro = 'FONTE'` | ✅ | A Casa registrou o voto |
| Período em `mandato_exercicio` | ✅ | Exerceu mandato, mesmo sem voto registrado (ex.: mandato anterior a 2001) |
| Voto **só** `DERIVADO` (ausência calculada por nós) | ✅ | **Sim** — e é o caso que exige justificativa |

O último merece a linha: uma pessoa cujo único registro é "faltou a estas
sessões" tinha mandato — a ausência só é derivável porque ela estava em
exercício. Marcá-la como "sem atuação legislativa" esconderia um mandato
inteiro, e seria uma omissão pior que o excesso. Ela cai no caso do
`mandato_exercicio` de qualquer forma, mas a regra fica explícita para não ser
"corrigida" depois por engano.

### O que fazer

1. **Função SQL `marcar_atuacao_legislativa()`**, ao lado de
   `reconstruir_perfil_leitura()` — mesmo lugar, mesma natureza: passo de
   finalização, versionado como schema e não como string em Java. Devolve
   quantas linhas mudaram.
2. **Migration V11** com a função. Não há `ADD VALUE` de enum aqui, então uma
   migration só resolve.
3. **`FinalizadorDeIngestao`** no pacote `publicacao`: marca a atuação e
   reconstrói a projeção, **nessa ordem** — a projeção copia a coluna, e
   inverter publicaria o valor velho.
4. Substituir as chamadas soltas a `ProjecaoDeLeitura.reconstruir` pelo
   finalizador.

### Um defeito que entra junto

**`OrquestradorDaAlesp` não reconstrói a projeção.** Foi introduzido no W12 e
passou despercebido porque nenhum teste da Alesp olha `perfil_leitura`. Hoje,
depois de uma ingestão da Alesp, o perfil publicado fica com o estado anterior.
O finalizador do A1 é exatamente o lugar de corrigir isso — e a correção só é
verificável porque os dois passos passam a ser um só.

### Como se prova

- Pessoa com autoria, pessoa com voto de FONTE, pessoa só com mandato e pessoa
  só com voto DERIVADO → todas marcadas.
- Pessoa da coorte sem nenhum registro → continua `false`.
- Quem perde o último registro (poda, correção retroativa) **volta** a `false`:
  a marcação é recálculo, não acumulação. Sem isso a flag só cresce e passa a
  mentir na direção oposta.
- Rodar duas vezes dá o mesmo resultado.
- Ponta a ponta: `OrquestradorDaAlesp` e `JobIncremental` deixam
  `perfil_leitura` coerente com `politico`.

### O que foi entregue

| Peça | Onde |
|---|---|
| `marcar_atuacao_legislativa()` | V12, e espelhada em `schema.sql` |
| `FinalizadorDeIngestao` | `publicacao/` — marca, DEPOIS reconstrói a projeção |
| `JobIncremental` rewired | usa o finalizador no lugar da chamada solta a `ProjecaoDeLeitura` |
| `OrquestradorDaAlesp` corrigido | passou a chamar o finalizador — **não chamava nada antes** |
| Invariantes T66–T69 | os três sinais, recálculo para baixo, idempotência |
| `FinalizadorDeIngestaoTest` (3 testes) | prova a ORDEM: reconstruir antes de marcar publicaria o valor velho |
| `OrquestradorDaAlespTest` (2 testes) | ciclo HTTP completo — a única forma de provar que a correção da Alesp realmente fecha a ingestão |

**Um retrabalho no meio do caminho:** `OrquestradorDaAlesp.executar` não tinha
como receber URLs de teste — diferente do `JobIncremental`, que já aceitava
`EnderecosDoAno`. Sem isso não havia como provar a correção sem bater no
portal real da Alesp a cada build. Ganhou o mesmo padrão: um record
`Enderecos` com fábrica `daAlesp()`, e uma sobrecarga de `executar` que o
recebe — o comportamento de produção não mudou, só passou a ser testável.

**Um defeito achado ao rodar, não ao ler:** `PoliticoRepositorio.perfilNormalizado`
ainda construía `PoliticoPerfil` com o construtor de 7 argumentos de antes da
remoção de `foto_url` — compilava porque `mvn compile` incremental não
recompilou aquele arquivo, e só apareceu como `Unresolved compilation problems`
em tempo de execução, no primeiro teste de integração da API. Corrigido; e
"compilar limpo" a partir de agora significa `mvn clean test`, não `-q compile`.

---

## A3 — o Senado ingerindo sozinho ✅ Entregue (01/09/2026)

### O problema

`JobDoSenado` carrega votações e votos, tem 11 testes contra amostra real e
trata voto secreto como categoria própria. E **nada o chama**: não existe
cliente HTTP, e ninguém cria `identificador_externo` com `sistema = 'SENADO'`.
Sem vínculo, `politicoDe(codigo)` devolve `null` e todo voto é ignorado — em
silêncio, porque estar fora da coorte é o caso esperado.

### A3.1 — cliente HTTP

Pacote `senado` (já existia por causa do `JobDoSenado` do W11):

- `EnderecosDoSenado` — as URLs num lugar só, como `ArquivosDaCamara`. Não é
  um `record` de URIs fixas como o da Alesp: carrega uma `base` parametrizável
  (`producao()` / `comBase(...)`), pelo mesmo motivo do `OrquestradorDaAlesp.Enderecos`
  — sem isso não haveria como testar o ciclo sem bater no Senado real.
- `ClienteDoSenado` — `GET` com `Accept: application/json`, e uma checagem que
  não estava no plano original: o `Content-Type` da **resposta** precisa
  conter `json`, não só o `Accept` do pedido.

**`Accept: application/json` não é preferência, é requisito**, e a verificação
de 01/09/2026 confirmou por que: a mesma URL responde
`Content-Type: application/json` com `Accept: application/json` e
`Content-Type: text/csv` com `Accept: text/csv` — e o CSV **descarta
silenciosamente o array `votos`**, com HTTP 200. `ClienteDoSenado` rejeita
qualquer resposta cujo `Content-Type` não contenha `json`, independente de
quem causou a divergência (código nosso errado, proxy, cache) — é defesa
contra o efeito, não só contra a causa já vista.

### A3.2 — o watermark, que aqui é diferente

Sem `Last-Modified`, o padrão dos outros dois conectores não se aplica.

**Watermark = maior `dataSessao` ingerida**, não o instante da coleta — que
seria um relógio nosso adivinhando o da fonte, o que o `BaixadorDeArquivos`
existe para evitar nos outros conectores.

Diferente do desenho original: em vez de reprocessar só "a janela do último
dia", `OrquestradorDoSenado` recarrega o **ano inteiro** a cada ciclo
(`/votacao?ano=<ano>`). Verificado: um ano do Senado são dezenas de votações,
não centenas de milhares de linhas — recarregar tudo é upsert idempotente e
barato, e evita qualquer lógica de janela parcial. `houveMudanca` compara a
maior `dataSessao` do que voltou contra o watermark anterior; sem sessão mais
nova, o marcador não avança — mesma regra do `JobIncremental`, mesma razão:
mover o marcador sobre janela vazia é execução falha fingindo sucesso.

### A3.3 — cadastro e identidade

Confirmado na verificação: **o Senado casa determinístico**, porque
`/senador/{codigo}` publica `DataNascimento` completa (dia, mês e ano).

**O universo mudou em relação ao plano original.** A ideia de somar
`/senador/lista/atual` a `/senador/lista/legislatura/{n}` foi substituída por
uma descoberta melhor: `/senador/lista/legislatura/{inicio}/{fim}` aceita um
**intervalo**, e `/plenario/lista/legislaturas` mostra as datas de cada uma.
A legislatura 50 começa em 1995-02-01 — quase exatamente onde a cobertura de
candidaturas federais do TSE começa (1994-01-01). Um único `GET` a
`/senador/lista/legislatura/50/57` traz as ~915 pessoas que estiveram no
Senado (titular ou suplente) dentro da janela que a coorte pode alcançar; a
lista "atual" (81 nomes) fica redundante, porque a legislatura 57 — a atual —
já está dentro do intervalo.

**Um problema que a verificação revelou e o plano não previa:** para
parlamentares em exercício, a própria resposta de `/senador/lista/legislatura/...`
já vem com `EmailParlamentar` e `UrlFotoParlamentar` dentro de
`IdentificacaoParlamentar` — não só o `/senador/{codigo}` de detalhe.
`RedatorDeCamposSensiveis` filtra por nome de campo no primeiro nível: se
`IdentificacaoParlamentar` fosse declarado na allowlist, o bloco inteiro
(e-mail e foto inclusos) seria copiado sem filtro nenhum, porque a allowlist
não desce dentro de um campo aninhado permitido. A solução não foi estender o
redator — seria mexer numa peça crítica e já validada por mais motivo que o
necessário — foi o orquestrador nunca lhe entregar a resposta aninhada:
`OrquestradorDoSenado.achatar` monta um registro RASO com só cinco campos
(`codigoParlamentar`, `nomeParlamentar`, `nomeCompletoParlamentar`,
`ufParlamentar`, `dataNascimento`), e é esse registro — nunca a resposta da
API — que chega ao staging.

**Custo de chamadas, resolvido diferente do previsto.** O plano original
cogitava buscar detalhe só para quem passasse por um filtro de nome — um
pré-filtro especulativo, com limiar próprio, que não existia em lugar nenhum
do código. A verificação levou a uma solução mais simples e mais correta:
data de nascimento é **imutável**, então quem já tem `identificador_externo`
(resolvido ou pendente de curadoria) ou já está em
`staging.registro_rejeitado` sem `resolvido_em` (fora da coorte ou ambíguo)
**nunca precisa ser rebuscado**. As duas consultas usam índices que já
existiam — nenhuma estrutura nova. Na prática, isso significa: a primeira
execução paga até ~915 chamadas de detalhe (≈3 minutos, medido); todo ciclo
seguinte paga só quem é genuinamente novo no universo. Uma correção de
curadoria (que zera `resolvido_em`) faz a pessoa voltar a ser reprocessada no
ciclo seguinte — de propósito.

### A3.4 — orquestrador e wiring

`OrquestradorDoSenado` na ordem que as FKs exigem — cadastro, depois votações
— e o ramo `INCREMENTAL` do `SeletorDeJob` passou a aceitar `--fonte=SENADO`
(usa `--ano`, como a Câmara). Termina no `FinalizadorDeIngestao` do A1, então
`possui_atuacao_legislativa` e `perfil_leitura` saem coerentes também aqui.

### O que foi entregue

| Peça | Onde |
|---|---|
| `EnderecosDoSenado` (base parametrizável) | `senado/` |
| `ClienteDoSenado` (checa `Content-Type` da resposta) | `senado/` |
| `LeitorDeSenadores` (registro raso → `ParlamentarDaCasa`) | `identidade/` |
| `OrquestradorDoSenado` (universo filtrado, detalhe, cadastro, votos, watermark, finalização) | `senado/` |
| Allowlist `SENADO:parlamentar` (5 campos) | `RedatorDeCamposSensiveis` |
| `--fonte=SENADO` no `SeletorDeJob` | ramo `INCREMENTAL` |
| `senado-parlamentares-lista-amostra.json` + `senado-parlamentares-detalhe-amostra.jsonl` | `db/golden/` — coerentes com `senado-votacao-amostra.jsonl`: o código 5672 aparece nos três |
| `ClienteDoSenadoTest` (4 testes) | prova a recusa do CSV e os endereços verificados |
| `OrquestradorDoSenadoTest` (5 testes) | ciclo HTTP completo: vínculo determinístico, watermark, janela sem novidade, economia de chamadas de detalhe, PII fora do staging |

### Como se prova

- CSV com `Content-Type: text/csv` é recusado, mesmo com HTTP 200.
- Vínculo do Senado sai `DETERMINISTICO`, não `FUZZY` — provado contra a API
  real (Alan Rick, nome completo + nascimento).
- E-mail, telefone e foto não chegam ao staging, mesmo vindo na resposta real
  da fonte para quem está em exercício.
- Watermark avança para a maior `dataSessao`; ciclo sem sessão mais nova não o
  move.
- Segundo ciclo não rebusca detalhe de quem já foi resolvido — provado
  derrubando o endpoint de detalhe entre os dois ciclos e mostrando que o
  orquestrador não quebra nem precisa dele.

## A2 — orquestração do backfill ✅ Entregue (01/09/2026)

### O problema

```java
case BACKFILL -> throw new IllegalArgumentException(
    "o backfill roda por ano e por conjunto de arquivos ja baixados; "
    + "use o JobDeBackfillCamara diretamente ate a fatia de orquestracao");
```

A mensagem descreve a lacuna com honestidade. As peças existem e são testadas
(20 testes no `JobDeBackfillCamara`); falta o laço por ano.

### O que fazer

`JobIncremental.executar` já faz, para **um** ano, exatamente o que o backfill
precisa: baixa, carrega matérias, votações e votos, deriva ausência. As
diferenças são três, e nenhuma é grande:

| | Incremental | Backfill |
|---|---|---|
| Janela | ano corrente | 2001 até hoje (Câmara); antes disso a fonte não publica voto nominal |
| Condicional de download | `If-Modified-Since` com o watermark | força o download: o histórico nunca foi lido |
| Derivação e projeção | por execução | **uma vez ao fim**, não por ano |

O último ponto é o que exige cuidado: derivar ausência e reconstruir a projeção
a cada ano seria 25 varreduras completas para publicar o mesmo estado final.
Pior, a derivação intermediária é temporariamente errada — ela cruza votações
com mandatos, e enquanto faltam anos o universo está incompleto.

Então: `JobDeBackfill` percorre os anos carregando, e **só ao terminar** deriva
e finaliza. O ano vira parâmetro de retomada (`--desde=2001`), porque um
backfill de 25 anos que morre no ano 19 não pode recomeçar do zero.

### Como se prova

- Dois anos carregados numa execução resultam no mesmo banco que dois
  incrementais consecutivos.
- Derivação e finalização rodam **uma vez**, não por ano.
- Interromper e retomar de `--desde` não duplica nem perde ano.
- O watermark reflete o último ano lido.

### Uma correção ao próprio raciocínio deste plano, achada relendo o código

A justificativa original ("derivar por ano deixaria a derivação intermediária
**errada**") não resiste à leitura de `DerivadorDeAusencia.derivar`: ele
**recalcula do zero** a cada chamada (`DELETE` de todas as linhas derivadas da
Casa, depois `INSERT` a partir do estado atual de `votacao` e
`mandato_exercicio`). Chamar duas vezes não deixaria rastro errado — a última
chamada corrigiria qualquer coisa que a primeira tivesse deixado incompleta.

O motivo real de rodar **uma vez, ao fim** é mais simples e ainda assim
suficiente: chamar a cada ano seria até 25 recomputações completas para
publicar o mesmo estado final — trabalho jogado fora — e deixaria uma janela,
por menor que fosse, em que alguém consultando o banco **durante** o backfill
veria uma derivação parcial (só dos anos já carregados). Nenhum dos dois é bug
de resultado; os dois são desperdício e exposição desnecessários. A decisão de
código (uma vez ao fim) continua certa; a razão registrada agora é a
verdadeira.

### O que foi entregue

| Peça | Onde |
|---|---|
| `JobDeBackfill` (não reaproveita `JobIncremental` — ver abaixo) | `massa/` |
| `--fonte=CAMARA` exigido no `BACKFILL`; `--desde` (default 2001) e `--ate` (default ano corrente) | `SeletorDeJob` |
| `JobDeBackfillTest` (7 testes) | laço por ano, download sem condicional, retomada por `--desde`, `--desde > --ate` falha alto, derivação+finalização provadas via um político "faltante", reexecução idempotente |

**Por que não é `JobIncremental` num laço**, confirmado ao implementar: o
incremental usa `BaixadorDeArquivos.baixarSeMudou(uri, destino, desde)` com
condicional — pergunta "mudou desde ontem?", que não faz sentido para um ano
nunca lido. `JobDeBackfill` baixa com `desde = null` sempre, forçando os cinco
arquivos do ano a cada iteração, e só chama `derivador.derivar` e
`finalizador.finalizar` depois que **todo** ano do intervalo terminou.

**Parametrizado do mesmo jeito que `OrquestradorDaAlesp` e
`OrquestradorDoSenado`**: `executar` tem uma sobrecarga que recebe
`IntFunction<EnderecosDoAno>` em vez de sempre usar `ArquivosDaCamara`. Sem
isso `JobDeBackfillTest` teria que baixar anos reais da Câmara a cada build —
o mesmo problema, resolvido do mesmo jeito, pela terceira vez nesta série de
fatias.

**Achado ao escrever o teste, não ao ler o código:** a amostra golden tem dois
conjuntos de pessoas DIFERENTES — quem vota (`votacoesVotos`) e quem assina
matéria (`proposicoesAutores`) não se sobrepõem. `carregarProposicoes` só
grava matéria cujo autor está na coorte, então o teste inicial, semeando só os
votantes, media zero matérias — não porque o código estivesse errado, mas
porque o teste não representava a coorte real. Corrigido semeando os dois
conjuntos.

## O que este plano deliberadamente não inclui

- **Fotos de candidato** — ❌ **cortadas por decisão de produto (01/09/2026):
  a plataforma não terá fotos.** A coluna `foto_url` saiu do schema e do
  contrato da API na V11, em vez de ficar nula para sempre. O cartão mostra as
  iniciais do nome, e isso deixou de ser *fallback*. Consequência colateral:
  some o problema do `next/image` em export estático, que não tem mais objeto.
- **Infraestrutura** — Dockerfile, IaC, CI/CD, agendamento e o fallback
  404→200 do CloudFront. Nada disso existe no repositório, e é o grupo B do
  levantamento: trabalho de infra, não de domínio.
- **Métricas de negócio** — quarentena por fonte e motivo, vínculos pendentes,
  defasagem. Documentadas em `BACKEND.md`, sem código. Também grupo B.
- **Backfill da Alesp por ano** — a Alesp publica a série inteira num arquivo
  só; o `OrquestradorDaAlesp` já carrega tudo. Não há backfill a orquestrar.

---

## Riscos

| Risco | Mitigação |
|---|---|
| A definição de "atuação legislativa" do A1 exclui alguém por engano | A regra é explícita, versionada em SQL e coberta por teste dos quatro sinais. Errar aqui apaga um mandato da lista de perfis |
| O Senado muda o formato da resposta sem avisar | Golden file já existe (`senado-votacao-amostra.jsonl`) e o T51 falha se surgir rótulo novo. O cliente entra com teste do caso CSV |
| Backfill de 25 anos estoura tempo ou memória | Carga por ano com `COPY` para tabela temporária (já é assim); retomada por `--desde` limita o prejuízo de uma interrupção |
| O watermark por `dataSessao` reprocessa a janela do último dia | Aceito: o upsert é idempotente e o custo é uma janela pequena. A alternativa — relógio nosso — é pior |
