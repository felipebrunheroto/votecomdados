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

## 3. Os três problemas de vínculo — medidos, não estimados

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

### 3.2 O código de autor não é o nosso

O campo `autor` é um código — ✅ a consulta pública do Portal usa formas
como `Autor: 8100`, que não tem a ordem de grandeza dos ids da Câmara
(~204554) nem dos do Senado.

❓ **A que sistema esse código pertence é a principal incógnita do
discovery.** Duas saídas:

- **Se for um código estável e mapeável**, vira mais uma linha em
  `identificador_externo` com um novo valor de `fonte_enum`, e o vínculo é
  determinístico. Cenário bom.
- **Se não for**, sobra casar por `nomeAutor` — texto livre, com homônimos,
  nomes parlamentares e grafias variantes. Seria reusar a máquina de
  resolução de identidade do COORTE (`RepositorioDeCoorte`, score de
  confiança, `metodo_resolucao = FUZZY`, curadoria manual do que ficar
  abaixo do limiar). Funciona — já funciona hoje —, mas é o caminho caro, e
  traz de volta o trabalho de curadoria.

Com 1.066 pessoas o problema é tratável nos dois cenários. Não é o caso de
20 mil.

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

## 6. Recomendação

**Um spike de um dia, antes de comprometer qualquer coisa**, para fechar as
três incógnitas ❓ que decidem o tamanho do trabalho:

1. Cadastrar a chave e medir **volume e tamanho de página** de um ano.
2. Descobrir **o que é o campo `autor`** — determinístico ou fuzzy define se
   o esforço é de dias ou de semanas.
3. Amostrar `localidadeDoGasto` e ver **em que formato** vem o município.

Com essas três respostas o dimensionamento deixa de ser chute. Sem elas,
qualquer estimativa que eu desse aqui seria inventada.

Duas coisas que valem independentemente do resultado: a tabela de
correspondência de municípios e a decisão de enquadramento do §5 — essa
segunda é sua, e é a que mais muda o produto.
