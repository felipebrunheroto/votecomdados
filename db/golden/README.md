# Golden files — amostras reais das fontes

Amostras **verbatim** dos arquivos oficiais, fixadas no repositório para que o
mapeamento de voto seja testado contra o dado que existe, e não contra o dado
que imaginamos.

A motivação está na recomendação R7 de
[REVISAO_ARQUITETURA.md](../../docs/REVISAO_ARQUITETURA.md): o maior risco
deste produto (B5) é de **correção de domínio**, não de código. Um mapeamento
errado não quebra teste unitário nenhum — ele atribui a um parlamentar uma
conduta que ele não teve. Só dado real pega isso.

Foi o que aconteceu na prática: ao inspecionar estes arquivos descobriu-se que
`votacoesVotos` **não tem rótulo de ausência** (achado B8), algo que nenhuma
revisão de documento pegaria.

| Arquivo | Origem | Coletado em |
|---|---|---|
| `camara-votacoesVotos-2026-amostra.csv` | `https://dadosabertos.camara.leg.br/arquivos/votacoesVotos/csv/votacoesVotos-2026.csv` | 30/08/2026 |
| `senado-votacao-amostra.jsonl` | `https://legis.senado.leg.br/dadosabertos/votacao?dataInicio=…&dataFim=…` | 31/08/2026 |
| `senado-parlamentares-lista-amostra.json` | `.../senador/lista/legislatura/50/57` | 01/09/2026 |
| `senado-parlamentares-detalhe-amostra.jsonl` | `.../senador/{codigo}` | 01/09/2026 |
| `camara-deputados-amostra.csv` | `https://dadosabertos.camara.leg.br/arquivos/deputados/csv/deputados.csv` | 30/08/2026 |
| `camara-deputado-historico-amostra.jsonl` | `https://dadosabertos.camara.leg.br/api/v2/deputados/{id}/historico` | 31/08/2026 |
| `camara-votacoes-2026-amostra.csv` | `https://dadosabertos.camara.leg.br/arquivos/votacoes/csv/votacoes-2026.csv` | 31/08/2026 |
| `camara-proposicoes-2026-amostra.csv` | `.../arquivos/proposicoes/csv/proposicoes-2026.csv` | 31/08/2026 |
| `camara-proposicoesTemas-2026-amostra.csv` | `.../arquivos/proposicoesTemas/csv/proposicoesTemas-2026.csv` | 31/08/2026 |
| `camara-proposicoesAutores-2026-amostra.csv` | `.../arquivos/proposicoesAutores/csv/proposicoesAutores-2026.csv` | 31/08/2026 |
| `tse-consulta-cand-2026-amostra.csv` | `consulta_cand_2026.zip` do TSE (baixado pelo owner) | 31/08/2026 |
| `alesp-votacoes-comissao-amostra.xml` | `.../repositorioDados/processo_legislativo/comissoes_permanentes_votacoes.xml` | 31/08/2026 |
| `alesp-reunioes-comissao-amostra.xml` | `.../processo_legislativo/comissoes_permanentes_reunioes.xml` | 31/08/2026 |
| `alesp-proposituras-amostra.xml` | `.../processo_legislativo/proposituras.zip` | 31/08/2026 |
| `alesp-documento-autor-amostra.xml` | `.../processo_legislativo/documento_autor.zip` | 31/08/2026 |
| `alesp-naturezas-amostra.xml` | `.../processo_legislativo/naturezasSpl.xml` | 31/08/2026 |
| `alesp-deputados-amostra.xml` | `.../repositorioDados/deputados/deputados.xml` | 31/08/2026 |

## Câmara — votações e votos: amostra coerente entre si

`camara-votacoes-2026-amostra.csv` e `camara-votacoesVotos-2026-amostra.csv`
formam um par: **uma votação de plenário com todos os seus 376 votos** —
cobrindo os cinco rótulos que a Casa emite — mais uma votação sem voto nominal.

A coerência é o ponto. Amostras montadas separadamente (votos de votações que
não estão no outro arquivo) fariam o teste da transformação passar por
acidente, sem nunca exercitar o `JOIN` que liga os dois.

A votação escolhida também carrega os dois casos que o mapeamento precisou
tratar: `votosOutros` = 21 com "Abstenção: 3" na descrição, e
`idProposicao` = `0` como sentinela de ausência.

## Como a amostra foi montada

Duas linhas por rótulo distinto encontrado no ano — os cinco que a Câmara
emite: `Sim`, `Não`, `Abstenção`, `Artigo 17` e `Obstrução`. Delimitador,
aspas e acentuação preservados como vieram.

**Não há linha com "ausente", e essa ausência é o ponto.** A fonte lista apenas
quem registrou voto; a mediana é de 398 linhas para 513 cadeiras. Ausência e
licença são derivadas (ver `mandato_exercicio`), não ingeridas.

## O que é verificado

[`db/test_invariantes.sql`](../test_invariantes.sql), T40: **todo rótulo
presente na amostra resolve em `mapeamento_voto`**. Um rótulo novo na origem —
ou um `INSERT` de seed removido por engano — falha o teste em vez de virar
quarentena silenciosa em produção.

## Câmara — cadastro: a amostra dos casos difíceis

`camara-deputados-amostra.csv` são 8 linhas escolhidas por conterem o que a
resolução de identidade precisa enfrentar: nome com acento, deputado **sem data
de nascimento** e a coluna `cpf` vazia.

Essa última é o ponto. É por ela que o casamento TSE↔Câmara **não pode** usar
CPF, e um teste afirma que a coluna continua vazia — se um dia vier preenchida,
o teste falha e avisa que a resolução pode melhorar.

Usada por `ResolucaoDeIdentidadeTest`, que lê o arquivo **verbatim** em vez de
reescrevê-lo como fixture sintética: fixture sintética prova que o código
funciona com o dado que imaginamos, e o risco aqui é o dado que existe.

## Câmara — histórico de situação: a fonte da ausência

`camara-deputado-historico-amostra.jsonl` traz o histórico de 4 deputados (63
eventos), escolhidos por cobrirem todas as situações que a fonte emite:
`Exercício`, `Licença`, `SUPLENCIA`, `CONVOCADO`, `FIM_MANDATO`, `VACANCIA` —
e eventos **sem situação**, que existem e não são transição de status.

É a fonte que torna possível dizer que alguém faltou (B8), e o formato tem três
armadilhas que a amostra preserva de propósito:

1. **Eventos sem `situacao`** ("Nome no início da legislatura") são metadados de
   nome e partido. Tratá-los como transição criaria períodos fantasma.
2. **A mesma `dataHora` se repete** — no exemplo, seis eventos em
   `2023-02-01T00:00`. Sem ordenação estável, o período resultante muda a cada
   execução.
3. **Transições no mesmo dia**: `CONVOCADO` às 14:56 e `Exercício` às 15:15.
   Como os períodos são em DATA, isso geraria um intervalo vazio.

## Câmara — proposições, temas e autoria: um trio coerente

As três amostras cobrem as mesmas matérias, e foram escolhidas por conterem o
que a carga precisa enfrentar: matéria com **mais de um tema** (a relação N:N
que o B3 apontou) e matéria com **vários autores**.

Duas observações do dado real, ambas registradas em teste:

- `proposicoesTemas` **não tem coluna de id** da proposição, só `uriProposicao`.
- Em 2026, **nenhuma matéria mistura** autor deputado com não-deputado. Quando
  o autor é senador, órgão ou Executivo, todos os autores são assim. Isso muda o
  que "coautor fora da coorte" significa na prática: é um deputado que não se
  recandidatou.

## TSE — a única amostra com dado redigido

`tse-consulta-cand-2026-amostra.csv` são 7 candidaturas reais, uma por cargo,
com as **50 colunas preservadas**. Só que três valores foram substituídos:
`NR_CPF_CANDIDATO`, `NR_TITULO_ELEITORAL_CANDIDATO` e `DS_EMAIL`.

A troca não é zelo excessivo: o arquivo do TSE traz o CPF de cada candidato, e
guardar amostra crua no repositório contradiria o B1 — a plataforma inteira é
construída sobre a premissa de que esse dado não é persistido. Nomes ficam, que
são públicos por definição em candidatura.

Os valores sintéticos preservam o **formato** (11 e 12 dígitos), que é o que o
teste precisa exercitar. Um script conferiu que nenhum identificador real do
pacote aparece na amostra.

**O que esta amostra provou**, e nenhuma fixture sintética teria pego:

- os sentinelas são `#NE` e `#NULO`, **sem** o `#` final que o código presumia;
- `DS_SITUACAO_CANDIDATURA` vem `#NE` em **100%** das 20.809 candidaturas;
- a codificação é **latin-1**, não UTF-8;
- `_BRASIL.csv` é a união exata dos arquivos por UF;
- o zip traz um `leiame.pdf` junto dos CSVs;
- a allowlist citava duas colunas que **não existem** no arquivo.

## Senado: amostra sem invariante, por enquanto

`senado-votacao-amostra.jsonl` são **4 votações que cobrem os 13 rótulos** que o
Senado emite, incluindo uma secreta — o conjunto mínimo que exercita o
vocabulário inteiro. Veio do spike do A12 (31/08/2026).

Formato **JSON Lines** (uma votação por linha), e não JSON indentado, para que
o teste consiga carregá-la sem preprocessamento — o array `votos` fica aninhado
dentro de cada linha, que é onde os votos individuais vivem.

Verificado por **T51**: os 13 rótulos ou têm tradução em `mapeamento_voto`, ou
estão no conjunto explicitamente não traduzido — que hoje é exatamente `{NA}`.
Rótulo novo na fonte quebra o teste, em vez de virar quarentena silenciosa.

**Cuidado documentado:** a API do Senado responde `Accept: text/csv`, e o CSV
**descarta silenciosamente o array `votos`** — devolve as votações sem nenhum
voto individual, com HTTP 200. Para o Senado, JSON não é preferência, é
requisito. `ClienteDoSenado` verifica o `Content-Type` da resposta por isso.

## Senado — cadastro: o universo e o detalhe são endpoints diferentes

`senado-parlamentares-lista-amostra.json` é uma amostra de
`/senador/lista/legislatura/50/57` — o intervalo de legislaturas (1995–2027)
que bate com a cobertura de candidaturas federais do TSE. É o universo de
onde saem os candidatos de 2026 que já foram senadores.

`senado-parlamentares-detalhe-amostra.jsonl` (JSON Lines, um `/senador/{codigo}`
por linha) é o único lugar de onde sai `DataNascimento` — a lista de
legislatura não publica. **Data completa (dia, mês e ano)**, verificado em
01/09/2026: ao contrário da Alesp, o Senado permite casamento determinístico
por nome + nascimento, sem precisar de resolução por similaridade.

Os dois arquivos são a mesma pessoa por um motivo: o código 5672 (Alan Rick)
aparece nos dois **e** vota nas quatro votações de `senado-votacao-amostra.jsonl`
— verificado ao montar `OrquestradorDoSenadoTest`, que usa os três arquivos
juntos para provar o ciclo inteiro (cadastro → vínculo → voto → atuação
legislativa marcada), não pedaços que nunca se encontrariam em produção.

**E-mail, telefone e foto foram trocados por valores sintéticos.** A resposta
real de `/senador/{codigo}` traz os três para quem está em exercício — e para
`/senador/lista/legislatura/...` também, quando o parlamentar está ativo,
verificado ao montar a amostra. Nenhum tem uso na resolução de identidade, e
por isso o orquestrador nunca copia a resposta da API para o staging: monta um
registro raso só com os cinco campos que interessam
(`OrquestradorDoSenado.achatar`), e é esse registro raso — não a resposta
aninhada — que passa pela allowlist `SENADO:parlamentar`.

## Alesp: a amostra que desmentiu a premissa do plano

Seis arquivos, e **42 votos em 9 deliberações** — escolhidas por conterem todos
os casos difíceis dos 226.067 votos reais.

O plano previa mapear **477 rótulos em texto livre** e mandar a cauda para
quarentena. A amostra mostrou que a fonte publica **dois** campos de voto:

| Campo | O que é | Valores |
|---|---|---|
| `<TipoVoto>` | código, documentado no PDF da própria Alesp | **8** (F, C, S, O, P, T, A, B) |
| `<Voto>` | o que a documentação dela chama de "descrição do tipo do voto" | **477** |

O mapeamento passou a ser pelo código. Os dois são preservados:
`voto_origem_codigo` guarda o código, `voto_origem` o texto.

### O que a amostra cobre, e por quê

| Caso | Onde | Por que está aqui |
|---|---|---|
| Os **7 códigos** em uso | 42 votos | `O` ("Outros") não ocorre na fonte; se aparecer, T61 falha |
| **F e P na mesma deliberação** | `8501-773645`, `8585-737301` | Podem ser **opostos**: "Favorável ao projeto e contrário ao parecer" vem como P ao lado de um F |
| Reunião **sem data** | `6599-303086` | 3 reuniões dos votos não existem no arquivo de reuniões (361 votos) |
| Documento **sem propositura** | `7320-303553` | 2.118 dos 18.973 documentos votados são pareceres e ofícios |
| `IdDocumento` **duplicado** | `10055`, duas vezes | 12.413 ids aparecem duas vezes, byte a byte idênticos |
| Data **sentinela** `0001-01-01` | `1000429543` | 6 proposituras sem data de publicação |
| **Colisão de `IdDeputado`** | Carlão Pignatari × Enio Tatto | ver abaixo |

### A armadilha do `IdDeputado`

`<IdDeputado>` tem significados **diferentes** em arquivos diferentes da mesma
fonte:

- em `deputados.xml` é o id do **portal**;
- em `comissoes_permanentes_votacoes.xml` e `documento_autor.xml` é o id do
  **SPL**, que o cadastro publica como `<IdSPL>`.

Dos 94 deputados em exercício, **7 têm um `IdDeputado` que colide com o id SPL
de outra pessoa**. A amostra traz dois: Carlão Pignatari tem `IdDeputado` 431, e
431 é o id SPL de **Enio Tatto** — casar pelo campo de nome óbvio daria a um os
101 mil registros de autoria do outro. `JobDaAlespTest` fixa isso.

### Dado pessoal substituído

`alesp-deputados-amostra.xml` é o único arquivo da Alesp com dado pessoal:
`Email`, `Telefone`, `PlacaVeiculo` e `Biografia` foram trocados por valores
sintéticos. Os campos **continuam presentes** de propósito — é o que permite ao
teste provar que a allowlist os descarta antes do staging.

O `PlacaVeiculo` é o melhor argumento da allowlist que este projeto tem:
ninguém esperaria placa de carro num cadastro parlamentar, e ela está lá.

## Ao atualizar

Reextrair da fonte oficial e atualizar a data acima. Se aparecer rótulo novo, o
teste falha primeiro — que é exatamente o comportamento desejado: rótulo sem
mapeamento é decisão editorial, e precisa de gente, não de `else`.
