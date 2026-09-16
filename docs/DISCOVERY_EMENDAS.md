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

### 3.2 O código de autor: resolvido — e é o cenário bom

✅ **`nomeAutor` traz o código embutido no próprio nome**, no formato
`"4290 - ABILIO BRUNINI"`. E `codigoEmenda` é composto: `202442900001` =
ano `2024` + autor `4290` + sequência `00001`.

Ou seja, o código de autor é **estável e estruturante** dentro do Portal —
não é um rótulo solto. Não é o id da Câmara (~204554) nem do Senado, mas é
um identificador consistente, e o par `(código, nome)` vem em toda linha.

O vínculo então é feito **uma vez, para ~1.066 pessoas**, e daí em diante é
determinístico: entra em `identificador_externo` com um novo valor de
`fonte_enum` (algo como `PORTAL_TRANSPARENCIA`), exatamente como já fazemos
com Câmara e Senado. O casamento inicial usa nome + UF + partido, com a
máquina de resolução do COORTE e curadoria do que ficar abaixo do limiar —
mas é trabalho **de uma vez só**, não a cada ingestão.

Isto era a incógnita que decidia "dias ou semanas". A resposta é **dias**.

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

### 3.4 Boa parte do dinheiro não tem cidade — o achado decisivo

✅ `localidadeDoGasto` assume pelo menos quatro formas, confirmadas por duas
fontes independentes:

| valor | significa |
|---|---|
| `"ITAMARAJU - BA"` | município — **o único caso atribuível a uma cidade** |
| `"BAHIA (UF)"` | o estado inteiro |
| `"Nacional"` | sem recorte territorial |
| `"Múltiplo"` | várias localidades numa linha só, sem discriminar |

**Só a primeira forma responde à pergunta original.** As outras três existem
em volume — e uma emenda `"Múltiplo"` não é divisível pela API: o rateio
entre as cidades simplesmente não é publicado nesse endpoint.

❓ **A fração de cada forma é a medição mais importante do spike** — e a que
importa não é fração de linhas, é **fração de dinheiro**. Se metade do valor
pago vier como `Múltiplo` ou `Nacional`, então "quanto foi para a sua
cidade" não é uma pergunta que esta fonte responde por inteiro, e a página
tem que **declarar a lacuna** em vez de mostrar um total que parece
completo. Silêncio aqui viraria subnotificação com cara de fato.

O script `tools/spike-emendas.py` mede exatamente isso.

### 3.5 Os valores vêm como texto brasileiro

✅ `valorPago` chega como `"2.359.960,00"` — string, com ponto de milhar e
vírgula decimal. `float("2.359.960,00")` estoura; pior, um parsing
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

## 6. Estado do spike

O script está pronto: **`tools/spike-emendas.py`**. A lógica de parsing já
foi testada contra os valores reais observados (`"2.359.960,00"` →
`2359960.0`; lixo → ausência, nunca zero) e contra as quatro formas de
localidade.

**Falta a chave da API** — é gratuita, sai por cadastro de e-mail em
`portaldatransparencia.gov.br/api-de-dados/cadastrar-email`, e leva um
minuto. Com ela:

```bash
export PORTAL_TRANSPARENCIA_TOKEN='...'
python3 tools/spike-emendas.py --ano 2025
```

O token é lido do ambiente e nunca é impresso. Se a funcionalidade for
adiante, o lugar dele é o Secrets Manager, como o pepper do CPF.

Das três incógnitas originais, **uma já caiu**: o campo `autor` é
estruturado e estável, então o vínculo é trabalho de dias, não de semanas.
Restam o volume e — a que de fato decide — **a fração do dinheiro que tem
cidade**.

Duas coisas independem do spike: a tabela de correspondência de municípios
(§3.3) e o enquadramento do §5. Essa segunda é sua, e é a que mais muda o
produto.
