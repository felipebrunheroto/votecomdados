# Discovery — emendas parlamentares por município

Pergunta original: *"quanto cada candidato repassou de verba para cada
cidade"*.

Os dados existem, são públicos e têm exatamente os campos necessários. O
trabalho não está em obtê-los — está em **ligá-los à nossa base** e em
**não afirmar mais do que eles dizem**.

Tudo abaixo marcado ✅ foi verificado ao vivo em 15/09/2026. O que está
marcado ❓ é incógnita que exige uma chave de API para medir.

---

## 1. O que "repassar verba" significa de fato

Um parlamentar **não repassa dinheiro**. Ele *indica* uma emenda ao
orçamento; quem empenha, liquida e paga é o Executivo. Entre a indicação e
o dinheiro na conta da prefeitura existem três estágios, e eles divergem
muito:

| estágio | o que significa |
|---|---|
| **empenhado** | o governo reservou o valor — compromisso, não dinheiro |
| **liquidado** | o serviço foi entregue e conferido |
| **pago** | o dinheiro saiu de fato |

Uma emenda pode ficar anos empenhada e nunca ser paga. **Publicar o valor
empenhado como "quanto fulano mandou para a cidade" seria falso** — e é o
erro mais comum em matéria sobre emendas.

Se a funcionalidade for adiante, a recomendação é mostrar **pago** como
número principal, com empenhado disponível ao lado e a diferença explicada.
É a mesma postura da nota de metodologia que já usamos em obstrução.

---

## 2. A fonte: API de Dados do Portal da Transparência (CGU)

✅ Endpoint: `GET https://api.portaldatransparencia.gov.br/api-de-dados/emendas`

✅ Campos retornados (extraídos do swagger ao vivo, `ConsultaEmendasDTO`):

```
codigoEmenda      ano            tipoEmenda      autor
nomeAutor         numeroEmenda   localidadeDoGasto
funcao            subfuncao
valorEmpenhado    valorLiquidado valorPago
valorRestoInscrito  valorRestoCancelado  valorRestoPago
```

**Autor + localidade + os três valores.** É precisamente o que a pergunta
pede, numa única chamada.

✅ **Autenticação obrigatória.** Sem chave: `401 {"Erro na API":"Chave de
API não informada!"}`. A chave é gratuita, obtida por cadastro de e-mail em
`portaldatransparencia.gov.br/api-de-dados/cadastrar-email`. Seria o
primeiro segredo de terceiro do projeto — vai para o Secrets Manager, como
o pepper.

✅ **Limites de uso:** 400 req/min no horário normal, **700 req/min entre
00:00 e 06:00**. O COORTE roda às 02:00, dentro da janela generosa. Uso
acima do limite suspende o token.

✅ **Filtros aceitos:** `codigoEmenda`, `numeroEmenda`, `nomeAutor`,
`tipoEmenda`, `ano`, `codigoFuncao`, `codigoSubfuncao`, `pagina`.

⚠️ **Não há filtro por município.** Para montar a visão "quanto foi para a
cidade X" é preciso puxar o ano inteiro e agregar do nosso lado.

❓ **Volume e tamanho de página** não medidos — exigem chave.

### Por que não o download em massa

O Portal publica CSVs anuais, que seriam o caminho natural (é o que fazemos
com TSE e Câmara). Mas ✅ as URLs de download responderam `500` ao `curl`, e
o JavaScript que monta o link respondeu `405`. A página carrega
`awswaf.com/challenge.js` — **o mesmo desafio de WAF que nos impede de
baixar o pacote do TSE automaticamente.**

Ou seja: o caminho em massa provavelmente cai na mesma armadilha já
documentada em `OPERACAO.md` §4.1. A API com chave é a rota sancionada e
não depende de navegador.

---

## 3. Os problemas de vínculo — medidos, não estimados

### 3.1 Só 5% da nossa base pode ter emenda

Medido nos CSVs públicos de `dados-abertos` de 15/09/2026:

| vínculos | quantidade |
|---|---|
| CAMARA | 984 |
| SENADO | 154 |
| ALESP | 78 |
| **pessoas distintas com mandato federal** | **1.066** |
| **pessoas na base** | **20.811** |

**1.066 de 20.811 — 5,1%.** Os outros 94,9% nunca exerceram mandato federal
e portanto *jamais terão uma emenda*, hoje ou depois.

Isso não inviabiliza a funcionalidade, mas define o desenho: a informação é
**exceção, não coluna**. Uma aba "Emendas" vazia em 19 de cada 20 perfis
seria ruído. O certo é a seção só existir quando houver dado, como já
fazemos com votações para quem não tem mandato.

### 3.2 O código de autor: resolvido, e é o cenário bom

✅ **Medido no ano de 2025 inteiro — 6.311 emendas.**

Uma correção primeiro: este documento afirmava que `nomeAutor` traz o código
embutido, no formato `"4290 - ABILIO BRUNINI"`. **Isso está errado.** Era
exemplo de terceiro, e a API ao vivo não devolve assim: `autor` e `nomeAutor`
trazem os dois o mesmo nome puro, em **6.311 de 6.311** linhas.

O código existe, mas em outro lugar — embutido em `codigoEmenda`:

```
202541840004  =  ano 2025 + autor 4184 + número 0004
                              └── LUIS CARLOS HEINZE
```

Confere-se contra `numeroEmenda`, que repete os quatro últimos dígitos:
**6.311 de 6.311** linhas batem.

E o teste que importa, a correspondência ser de um para um:

| | |
|---|---|
| códigos de autor distintos | **628** |
| nomes de autor distintos | **628** |
| códigos com mais de um nome | **0** |
| nomes com mais de um código | **0** |

**Zero ambiguidade nos dois sentidos.** O vínculo é feito uma vez, para ~628
autores, e daí em diante é determinístico: entra em `identificador_externo`
com um novo valor de `fonte_enum`. O casamento inicial ainda é por nome — não
há CPF aqui —, mas é trabalho de uma vez só, com um conjunto pequeno.

Os 628 incluem bancadas e comissões, que não são pessoas; `tipoEmenda` separa.

### 3.3 Não temos código de município

✅ `localidadeDoGasto` é **texto livre** (`type: string`), não código.

✅ Do nosso lado, `candidatura` tem `municipio` (texto) e
`codigo_municipio_tse` — e **nenhuma referência a código IBGE em todo o
schema** (verificado por varredura nas migrações e no código).

Então o cruzamento por município hoje é texto contra texto: `"SÃO
BENTO"` existe em quatro estados; acento, caixa e abreviação variam. Isso é
armadilha conhecida.

A correção certa é trazer uma **tabela de correspondência
IBGE ↔ TSE ↔ nome**, que é dado público e estável, e passar a chavear por
IBGE. É trabalho pequeno e útil além desta funcionalidade — o próprio
`municipio` atual já está sujeito ao mesmo problema.

---

### 3.4 Só 3,4% do dinheiro tem cidade — a resposta que decide

✅ **Medido, 2025 completo, 6.311 emendas, R$ 32,5 bilhões pagos.**

| forma de `localidadeDoGasto` | linhas | **valor pago** |
|---|---:|---:|
| `Múltiplo` | 40,7% | **88,5%** — R$ 28,73 bi |
| `ESTADO (UF)` | 35,9% | 7,5% — R$ 2,44 bi |
| **`CIDADE - UF`** | **12,0%** | **3,4% — R$ 1,12 bi** |
| `Nacional` | 11,0% | 0,6% — R$ 179 mi |
| outros | 0,3% | 0,0% |

**Só 3,4% do dinheiro é atribuível a um município.** A pergunta original —
"quanto cada candidato repassou para cada cidade" — não é respondível por esta
fonte para 96,6% do valor.

#### Não é decomponível

✅ `/api-de-dados/emendas/documentos/{codigo}` devolve apenas metadado de
documento — `codigoDocumento`, `data`, `fase`, `especieTipo`. **Nenhum campo
de localidade.** Uma emenda `Múltiplo` de R$ 1 milhão não se abre em cidades
por aqui.

#### Nenhum tipo de emenda salva o recorte

Testei se algum subconjunto se comportava melhor. Não:

| tipo | município (valor pago) |
|---|---:|
| Individual — Finalidade Definida | 5,6% |
| Individual — Transferências Especiais | 3,5% |
| Bancada | 1,5% |
| Comissão | **0,0%** |

Nem as Transferências Especiais — a "emenda PIX", que por lei vai a um
município — aparecem com cidade: são **94,1% `Múltiplo`** neste endpoint.

#### O que sobraria

| | |
|---|---|
| emendas com cidade | 759 |
| valor | R$ 1,12 bi |
| **cidades distintas** | **474** |
| parlamentares com ao menos uma | 275 de 628 |

**474 de 5.570 municípios — 8,5%.** Para nove em cada dez cidades brasileiras,
a página mostraria zero. E zero, aqui, seria mentira: não significa "nada foi
destinado", significa "esta fonte não diz para onde foi".

#### O caminho caro que existe

`codigoDocumento` é número de documento do SIAFI
(`257001000012025NE473065`). Cruzá-lo com `/api-de-dados/despesas/*` traria o
favorecido, e o favorecido tem município. Mas são ~15 documentos por emenda ×
2.570 emendas `Múltiplo` ≈ **38.500 requisições adicionais**, contra um
endpoint da lista **restrita** (180 req/min). Muda a natureza do projeto.

### 3.5 Os valores vêm como texto brasileiro

✅ `valorPago` chega como `"2.359.960,00"` — string, com ponto de milhar e
vírgula decimal. E há negativos, com o sinal **separado por espaço**:
`"- 26.002,00"`, que apareceu logo na primeira linha real devolvida pela API.
Em 2025 há uma única linha assim, de R$ 26 mil — que a primeira versão do
script transformava em ausência silenciosa. `float("2.359.960,00")` estoura; pior, um parsing
descuidado com `try/except` devolvendo `0` transforma **R$ 2,3 milhões em
zero silencioso**.

Zero indistinguível de "não há dado" é precisamente o tipo de número errado
que esta plataforma não pode publicar. O spike já trata isso: converte, e
devolve **ausência** — nunca zero — quando não consegue.

## 4. Duas assimetrias que precisam de decisão

**Estaduais ficam de fora.** Emendas de deputado estadual não estão nesta
API — o orçamento é do estado, e São Paulo publica em sistema próprio. Como
a plataforma cobre ALESP, a funcionalidade nasceria cobrindo Câmara e
Senado e **silenciosa para os 78 vínculos da ALESP**. Silêncio que parece
"não destinou nada" é pior que ausência declarada; se for adiante, a página
precisa dizer que a fonte não cobre o estadual.

**Emendas sem autor individual.** Emendas de bancada, de comissão e de
relator não têm um parlamentar responsável. O `tipoEmenda` permite separá-las;
o importante é não somá-las a ninguém.

---

## 5. A tensão com a premissa do produto

Vale dizer com clareza, porque é decisão sua e não minha.

A plataforma tem como premissa **não ranquear candidatos** — foi por isso
que a página inicial deixou de trazer 20 nomes antes de qualquer busca.

Valor em reais é inerentemente ordenável. Publicar "quanto cada um destinou"
cria, na prática, um ranking — e um ranking enganoso, porque:

- emenda individual tem **teto igual por parlamentar**, então o valor total
  mede pouco mais que tempo de mandato;
- quem tem mandato há mais tempo acumula mais, sem que isso diga nada sobre
  mérito;
- destinar muito para poucas cidades e pouco para muitas são estratégias
  diferentes, não melhores ou piores.

Isso **não é argumento contra a funcionalidade** — é argumento contra uma
apresentação específica. O enquadramento que escapa da armadilha é o
inverso do pedido original: em vez de *"quanto o candidato mandou"*,
**"quais emendas esta cidade recebeu, e de quem"**. A mesma tabela, a mesma
ingestão; a pergunta passa a ser do eleitor sobre o próprio município, que
é onde a informação tem uso real e onde não há ranking a fabricar.

---

## 6. Resultado do spike (25/09/2026)

Rodado contra 2025 completo: 6.311 emendas, 421 páginas, ~7 minutos com pausa
de 0,4s entre requisições (~150 req/min, contra teto de 400).

| incógnita | resposta |
|---|---|
| Volume | 6.311/ano. Não é restrição. |
| Autor | **Determinístico.** 628 códigos ↔ 628 nomes, zero ambiguidade. |
| Localidade | **Só 3,4% do dinheiro tem cidade.** |

### Recomendação: não construir a partir desta fonte

A funcionalidade como pedida mostraria 3,4% do dinheiro e ficaria calada sobre
o resto. Pior que incompleta, seria **enganosa**: para 91% dos municípios a
página diria zero, e o leitor entenderia "meus representantes não destinaram
nada", quando o correto é "esta fonte não diz para onde foi".

Essa plataforma não pode publicar esse tipo de número. É a mesma régua que já
levou a esconder a tag de status do TSE enquanto o dado não existia.

### Uma armadilha de método, registrada

A primeira execução parou em `HTTP 504` na página 136, com 32% do ano
coletado. Nessa amostra, `MUNICIPIO` aparecia com **22,6%** do valor. No ano
completo são **3,4%** — diferença de quase sete vezes.

A paginação não devolve as linhas em ordem aleatória, então amostra parcial
não é amostra representativa. Se eu tivesse concluído ali, a recomendação
teria sido a oposta.

