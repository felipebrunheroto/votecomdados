# API interna — Contrato REST

Base: `/api/v1`. Todas as respostas incluem, quando aplicável, a fonte
oficial (`urlFonte` / `urlTramitacao`) para o dado exibido — requisito de
transparência do projeto.

**Escopo do MVP:** a base contém apenas pessoas candidatas na eleição de 2026
(qualquer cargo). Para elas há a **trajetória eleitoral completa nos três
níveis** (municipal, estadual, federal) e a **atuação legislativa federal e
estadual em SP**. Demais assembleias e câmaras municipais ficam para a próxima
versão. Quem não é candidato em 2026 não tem perfil: aparece no máximo como
nome numa lista de autoria.

## GET /politicos

Busca e filtro de candidatos.

**Query params**
| Nome | Tipo | Obrigatório | Descrição |
|---|---|---|---|
| `q` | string | não | Busca textual (nome civil, de urna ou parlamentar); máx. 100 caracteres |
| `cargo` | enum | não | Cargo disputado em 2026: `PRESIDENTE`, `GOVERNADOR`, `SENADOR`, `DEPUTADO_FEDERAL`, `DEPUTADO_ESTADUAL`, ... |
| `uf` | string(2) | não | Filtro de UF (`BR` para cargos nacionais) |
| `comAtuacao` | boolean | não | Se `true`, retorna apenas candidatos com atuação legislativa registrada (federal ou estadual) |
| `page` | int | não (default 1) | Página |
| `pageSize` | int | não (default 20, max 100) | Itens por página |

**Resposta `200`**
```json
{
  "data": [
    {
      "id": "b3f1e2a0-...-uuid",
      "nomeCivil": "Maria da Silva Souza",
      "nomeUrna": "Maria Silva",
      "cargo2026": "DEPUTADO_FEDERAL",
      "uf": "SP",
      "partidoSigla": "XYZ",
      "statusCandidatura": "DEFERIDO",
      "possuiAtuacaoLegislativa": true
    }
  ],
  "pagination": { "page": 1, "pageSize": 20, "total": 187 }
}
```

`possuiAtuacaoLegislativa` distingue "não tem mandato legislativo anterior" de
"falhamos ao buscar" — a maioria dos candidatos cai no primeiro caso, e a UI
deve dizer isso de forma afirmativa em vez de mostrar abas vazias.

## GET /politicos/{id}

Perfil consolidado.

**Resposta `200`**
```json
{
  "id": "b3f1e2a0-...-uuid",
  "nomeCivil": "Maria da Silva Souza",
  "nomeUrna": "Maria Silva",
  "possuiAtuacaoLegislativa": true,
  "trajetoria": [
    {
      "anoEleicao": 2026,
      "cargo": "GOVERNADOR",
      "esfera": "ESTADUAL",
      "uf": "SP",
      "municipio": null,
      "partidoSigla": "XYZ",
      "status": "NAO_INFORMADO",
      "eleito": null
    },
    {
      "anoEleicao": 2022,
      "cargo": "DEPUTADO_FEDERAL",
      "esfera": "FEDERAL",
      "uf": "SP",
      "municipio": null,
      "partidoSigla": "XYZ",
      "status": "DEFERIDO",
      "eleito": true
    },
    {
      "anoEleicao": 2016,
      "cargo": "VEREADOR",
      "esfera": "MUNICIPAL",
      "uf": "SP",
      "municipio": "Campinas",
      "partidoSigla": "XYZ",
      "status": "DEFERIDO",
      "eleito": true
    }
  ],
  "cobertura": [
    { "esfera": "FEDERAL",   "uf": null, "casa": "CAMARA", "recurso": "voto_nominal",     "status": "DISPONIVEL",               "disponivelDesde": "2001-01-01", "observacao": "Votos nominais de plenário da Câmara só existem a partir de 2001." },
    { "esfera": "FEDERAL",   "uf": null, "casa": "SENADO", "recurso": "voto_nominal",     "status": "DISPONIVEL",               "disponivelDesde": "1991-01-01", "observacao": "Votos individuais desde 1991. Em 53% das votações a deliberação é secreta." },
    { "esfera": "ESTADUAL",  "uf": "SP", "casa": "ALESP",  "recurso": "proposicao",       "status": "DISPONIVEL",               "disponivelDesde": "1970-09-23", "observacao": "Proposituras e autoria no portal de dados abertos da Alesp. A série alcança 1970, mas é rala antes de 1995: 497 matérias em 25 anos, contra 278 mil no total." },
    { "esfera": "ESTADUAL",  "uf": "SP", "casa": "ALESP",  "recurso": "votacao_comissao", "status": "DISPONIVEL",               "disponivelDesde": "2006-02-15", "observacao": "Votos individuais em comissões permanentes da Alesp, desde fevereiro de 2006. A Casa publica o voto de cada membro da comissão, e não a lista de quem faltou — ausência em comissão não é derivada." },
    { "esfera": "ESTADUAL",  "uf": "SP", "casa": "ALESP",  "recurso": "voto_nominal",     "status": "NAO_PUBLICADO_PELA_FONTE", "disponivelDesde": null,         "observacao": "A Alesp publica as votações nominais de plenário apenas como PDF por votação, um por deliberação, sem dado estruturado de quem votou como. O registro existe e não é legível por máquina; a limitação é da forma de publicação da fonte, não da plataforma." },
    { "esfera": "MUNICIPAL", "uf": null, "casa": null,     "recurso": "proposicao",       "status": "FORA_DO_ESCOPO_MVP",       "disponivelDesde": null,         "observacao": "A atuação em câmaras municipais não é coberta nesta versão." }
  ]
}
```

**`status` pode vir `NAO_INFORMADO`, e isso não é falta de dado nosso.** O TSE
usa sentinela enquanto o registro de candidatura está sendo julgado — em
31/08/2026 era o caso de **100% das 20.809 candidaturas de 2026**. A UI deve
dizer "situação ainda não informada pelo TSE"; exibir "apto" seria a plataforma
afirmar em nome do órgão. Pelo mesmo motivo, `eleito` vem `null` até a eleição
ocorrer: `false` diria que a pessoa perdeu.

`trajetoria` é a vida política completa da pessoa, dos três níveis, ordenada
da disputa mais recente para a mais antiga. Vem do TSE, que é fonte uniforme
para municipal, estadual e federal. A entrada de 2026 é a que coloca a pessoa
na base e pode ser de **qualquer cargo**.

**`cobertura` é obrigatório na UI, não opcional.** Ele é filtrado para as
esferas, UFs **e Casas** relevantes ao candidato, e o campo decisivo é `status`.

**`casa` não é decoração.** A esfera federal tem duas Casas com coberturas
diferentes — voto nominal da Câmara desde 2001, do Senado desde 1991 — e quem
teve mandato nas duas recebe **duas linhas** de `voto_nominal`, uma por Casa.
A UI precisa dizer de qual Casa cada linha é: sem isso, um ex-deputado hoje
senador leria duas datas contraditórias sem saber a qual atuação cada uma se
refere. `casa` vem `null` quando a regra não é de Casa nenhuma (trajetória
eleitoral, que vem do TSE).

| `status` | O que a UI deve dizer |
|---|---|
| `DISPONIVEL` | Exibe o dado; usa `disponivelDesde` para explicar recortes (ex.: nada antes de 2001) |
| `NAO_PUBLICADO_PELA_FONTE` | "A Casa não publica esse dado" — limitação definitiva, não vale esperar |
| `FORA_DO_ESCOPO_MVP` | "Ainda não cobrimos" — trabalho nosso, previsto para a próxima versão |

Confundir os dois últimos é desonesto nos dois sentidos: dizer "não existe"
quando é "não fizemos", ou criar expectativa de que basta esperar por algo que
a fonte nunca publicará.

Sem exibir isso, a plataforma produz uma distorção grave: um vereador com dez
anos de mandato aparece com trajetória rica e nenhuma matéria, e o usuário lê
"não fez nada" quando o correto é "não cobrimos câmaras municipais". Comparar
candidatos de níveis ou estados diferentes sem esse contexto é comparar volume
de publicação de dados, não atuação política.

**Erros**: `404` se o `id` não existir — inclusive para quem não é candidato
em 2026, já que essas pessoas não têm perfil na base.

## GET /politicos/{id}/proposicoes

Aba "Projetos Apresentados".

**Query params**: `page`, `pageSize`, `tema` (opcional), `anoInicio`/`anoFim` (opcionais).

**Resposta `200`**
```json
{
  "data": [
    {
      "id": 987654,
      "siglaTipo": "PL",
      "numero": 1234,
      "ano": 2023,
      "ementa": "Dispõe sobre ...",
      "temas": ["Educação", "Direitos Humanos"],
      "dataApresentacao": "2023-04-10",
      "situacaoAtual": "Aguardando Parecer",
      "urlInteiroTeor": "https://...",
      "urlTramitacao": "https://www.camara.leg.br/proposicoesWeb/..."
    }
  ],
  "pagination": { "page": 1, "pageSize": 20, "total": 42 }
}
```

## GET /politicos/{id}/votacoes

Aba "Votações em Plenário" — o voto do político em cada votação nominal de
que participou.

**Query params**: `page`, `pageSize`, `anoInicio`/`anoFim` (opcionais).

**Resposta `200`**
```json
{
  "data": [
    {
      "votacaoId": 555111,
      "dataVotacao": "2023-06-15T14:32:00Z",
      "descricao": "Aprovação do requerimento de urgência para o PL 1234/2023",
      "casa": "CAMARA",
      "esfera": "FEDERAL",
      "ambito": "PLENARIO",
      "temas": ["Educação"],
      "tipo": "NOMINAL",
      "voto": "SIM",
      "votoOrigem": "Sim",
      "aprovada": true,
      "urlFonte": "https://www.camara.leg.br/..."
    },
    {
      "votacaoId": 777001,
      "dataVotacao": "2021-04-07T11:00:00Z",
      "descricao": "Parecer do relator na Comissão de Constituição e Justiça",
      "casa": "ALESP",
      "esfera": "ESTADUAL",
      "ambito": "COMISSAO",
      "temas": ["Educação"],
      "tipo": "NOMINAL",
      "voto": "SIM",
      "votoOrigem": "Favorável ao parecer",
      "notaMetodologica": "Voto em comissão, favorável ao parecer do relator — não é votação de plenário.",
      "aprovada": true,
      "urlFonte": "https://www.al.sp.gov.br/..."
    },
    {
      "votacaoId": 555113,
      "dataVotacao": "2023-06-18T16:40:00Z",
      "descricao": "Votação do PL 555/2023",
      "temas": ["Meio Ambiente"],
      "tipo": "NOMINAL",
      "voto": "OBSTRUCAO",
      "votoOrigem": "Obstrução",
      "notaMetodologica": "Obstrução é manobra regimental de orientação de bancada, não um voto contrário ao mérito.",
      "aprovada": false,
      "urlFonte": "https://www.camara.leg.br/..."
    },
    {
      "votacaoId": 555112,
      "dataVotacao": "2023-06-20T10:05:00Z",
      "descricao": "Redação final do PL 999/2023",
      "temas": ["Saúde"],
      "tipo": "SIMBOLICA",
      "voto": null,
      "votoOrigem": null,
      "observacao": "Votação simbólica: sem registro de voto nominal individual.",
      "aprovada": true,
      "urlFonte": "https://www.camara.leg.br/..."
    }
  ],
  "pagination": { "page": 1, "pageSize": 20, "total": 310 }
}
```

`voto` é um de
`SIM | NAO | ABSTENCAO | BRANCO | AUSENTE | LICENCIADO | AUSENCIA_JUSTIFICADA |
PRESENTE_NAO_VOTOU | SECRETO | OBSTRUCAO | VOTO_EM_SEPARADO | ART_17 | null`
(`null` somente quando `tipo = "SIMBOLICA"`, e nesse caso `observacao`
explica o motivo para a UI não mostrar uma lacuna sem contexto).

**`AUSENTE` e `LICENCIADO` são derivados por nós, não publicados pela fonte.**
`votacoesVotos` da Câmara lista apenas quem registrou voto — em 2026 há cinco
rótulos (`Sim`, `Não`, `Abstenção`, `Artigo 17`, `Obstrução`) e nenhum
"ausente", com mediana de 398 linhas para 513 cadeiras. As duas categorias saem
do cruzamento entre a votação e a lista de quem estava em exercício naquela
data; `LICENCIADO` corresponde a `situacao = "Licença"` no histórico da Casa, e
existe justamente para não apresentar como falta o que era licença. Quem não
estava em exercício **não aparece na lista** — não é ausência, é não ser
parlamentar naquele dia.

Nessas duas linhas `votoOrigem` vem `null` e `origemRegistro` vem `"DERIVADO"`;
em todas as demais, `origemRegistro` é `"FONTE"` e `votoOrigem` é obrigatório.
Em votação simbólica os três vêm `null` — não há registro individual algum,
nem da fonte nem derivado.

**No Senado é o contrário, e a UI precisa saber disso.** A fonte publica a
bancada inteira em cada votação, com licença e ausência declaradas pela Casa:
lá **nada é derivado**, tudo chega como `FONTE`. Três categorias existem só por
causa dele:

| Valor | Significa | Erro que evita |
|---|---|---|
| `SECRETO` | Participou de votação secreta; a Casa não publica a escolha | Ler participação como omissão |
| `PRESENTE_NAO_VOTOU` | Estava na sessão e não registrou voto | Contar presença como falta |
| `AUSENCIA_JUSTIFICADA` | Ausência por missão oficial ou atividade parlamentar | Contar trabalho da Casa como falta |

**`secreta` acompanha toda votação** e é `true` em **53% das votações de
plenário do Senado** — não é caso de borda. Quando `secreta` é `true`, `placar`
vem `null` pelo mesmo motivo da simbólica: exibir "0 a 0" sugeriria que ninguém
votou, quando houve deliberação cujo conteúdo a Casa não publica.
A UI deve marcar visualmente a diferença. *(Decorre da resposta 10 da revisão;
o schema correspondente foi aplicado em 31/08/2026 — ver
[REVISAO_ARQUITETURA.md](REVISAO_ARQUITETURA.md#-o-que-as-respostas-geraram--aplicado-em-31082026),
item P1.)*

**Na Alesp só há voto de comissão, e a fonte publica um código.** Ela emite
`<TipoVoto>` (8 códigos documentados) além de `<Voto>` (477 descrições em texto
livre). O enum vem do código; `votoOrigem` traz o texto. Dois valores existem
só por causa dela:

| Valor | Significa | Erro que evita |
|---|---|---|
| `VOTO_EM_SEPARADO` | Votou apresentando parecer escrito divergente do relator | Ler divergência como abstenção — é o oposto. A fonte **não diz** a direção do divergente, e há registros favoráveis e contrários |
| `BRANCO` | Voto em branco, que a Alesp conta separado da abstenção | Atribuir uma abstenção que o parlamentar não declarou |

**`ambito` deve ser sempre visível.** Voto em comissão não tem o mesmo peso
político de deliberação em plenário; listar os dois sem distinção inflaria a
atuação aparente de quem tem dados de comissão publicados. Hoje o único voto
individual estadual disponível é de comissão (ALESP), então a distinção é o
que impede uma comparação injusta com o nível federal.

**`votoOrigem` é obrigatório em todo voto registrado pela fonte
(`origemRegistro = "FONTE"`) e nunca deve ser omitido pela UI.** `voto` é a nossa normalização — uma interpretação —
enquanto `votoOrigem` é o rótulo literal da fonte oficial. Os vocabulários da
Câmara e do Senado diferem e carregam semântica de processo legislativo, então
apresentar apenas o enum atribuiria ao parlamentar uma conduta que ele pode não
ter tido. Quando o mapeamento tem nota de metodologia cadastrada, ela vem em
`notaMetodologica` e deve ser exibida junto.

## GET /votacoes/{id}

Detalhe de uma votação, incluindo placar agregado (uso interno / deep-link).

```json
{
  "id": 555111,
  "descricao": "Aprovação do requerimento de urgência para o PL 1234/2023",
  "casa": "CAMARA",
  "esfera": "FEDERAL",
  "ambito": "PLENARIO",
  "tipo": "NOMINAL",
  "dataVotacao": "2023-06-15T14:32:00Z",
  "placar": { "sim": 312, "nao": 145, "abstencao": 3, "outros": 53 },
  "aprovada": true,
  "proposicaoId": 987654,
  "urlFonte": "https://www.camara.leg.br/..."
}
```

`casa`, `esfera` e `ambito` são obrigatórios aqui pelo mesmo motivo da lista de
votações: sem `ambito`, um parecer de comissão apareceria com o mesmo peso de
uma deliberação de plenário.

`outros` agrupa ausências, obstruções e Art. 17 — posições que **não são**
manifestação sobre o mérito. Somá-las a "não" inflaria a oposição à matéria.

Em votação simbólica, `placar` vem `null` e `observacao` explica: a Casa não
registra voto individual, então não há contagem a exibir. A UI não deve
representar isso como zero votos.

```json
{
  "id": 555112,
  "descricao": "Redação final do PL 999/2023",
  "tipo": "SIMBOLICA",
  "placar": null,
  "aprovada": true,
  "observacao": "Votação simbólica: a Casa registra apenas o resultado, não o voto de cada parlamentar.",
  "urlFonte": "https://www.camara.leg.br/..."
}
```

## GET /proposicoes/{id}

Detalhe de proposição, incluindo lista de autores.

```json
{
  "id": 987654,
  "siglaTipo": "PL",
  "numero": 1234,
  "ano": 2023,
  "ementa": "Dispõe sobre ...",
  "autores": [
    { "politicoId": "b3f1e2a0-...", "nome": "Maria Silva", "autorPrincipal": true },
    { "politicoId": null, "nome": "Beltrano de Souza", "autorPrincipal": false }
  ],
  "urlTramitacao": "https://www.camara.leg.br/proposicoesWeb/..."
}
```

A lista de autores é **sempre completa**. `politicoId` vem `null` para
coautores que não são candidatos em 2026: eles aparecem pelo nome, como consta
na fonte, mas não têm perfil, histórico nem página — omiti-los distorceria o
registro da matéria, e mantê-los com perfil contrariaria a minimização de
dados. A UI não deve criar link para autores com `politicoId: null`.

## GET /proposicoes

## GET /votacoes

```json
{ "ids": [618609, 900001, 1197773, 2074843] }
```

**Não confundir com navegação.** As duas existem só para alimentar
`generateStaticParams` no build do frontend — devolvem **todos** os ids, sem
paginação e sem filtro, porque essa é a única finalidade delas (achado B1,
01/09/2026: o frontend já chamava estas rotas antes de existirem, e recebia
500). Não há tela que liste "todas as matérias" ou "todas as votações" sem um
candidato como contexto; quem quer navegar usa
`/politicos/{id}/proposicoes` ou `/politicos/{id}/votacoes`.

## GET /meta/status

Frescor dos dados: última ingestão bem-sucedida por fonte, lida de
`ingestao_execucao`. Consumido pela aplicação web **em tempo de execução**,
para que o indicador de atualização mostrado ao usuário reflita o estado
real do pipeline — e não a data em que o HTML estático foi gerado (ver
[ARQUITETURA.md § 8](ARQUITETURA.md#8-frescor-dos-dados-como-requisito-de-transparência)).

```json
{
  "fontes": [
    {
      "fonte": "CAMARA",
      "ultimaAtualizacao": "2026-08-16T04:12:33Z",
      "status": "CONCLUIDA"
    },
    {
      "fonte": "SENADO",
      "ultimaAtualizacao": "2026-08-16T04:15:02Z",
      "status": "CONCLUIDA"
    },
    {
      "fonte": "TSE",
      "ultimaAtualizacao": "2026-07-30T02:00:11Z",
      "status": "CONCLUIDA"
    }
  ]
}
```

Se a última execução de uma fonte falhou, `status` vem como `FALHA` e
`ultimaAtualizacao` continua apontando para a última execução **bem-sucedida**
— o dado exibido é daquela data, e a UI deve ser explícita sobre isso em vez
de sugerir que está atualizado.

### Dados abertos

O banco curado é publicado de volta como dado aberto — é o que permite auditar
de fora o cruzamento entre TSE e Casas, que é afirmação nossa (ver
[ARQUITETURA.md § 8b](ARQUITETURA.md#8b-dados-abertos-devolver-o-dado-consolidado)).

Não é endpoint da API: são arquivos estáticos na mesma CDN do site.

```
/dados-abertos/AAAA-MM-DD/          instantâneo imutável — endereço de citação
/dados-abertos/AAAA-MM-DD/manifesto.json
/dados-abertos/AAAA-MM-DD/LEIA-ME.md
/dados-abertos/latest/              conveniência; NÃO usar para citar
```

Cite sempre o diretório datado: `latest` muda, e um arquivo que muda embaixo de
quem o citou não serve de evidência.

## Convenções gerais

- Paginação sempre no formato `{ data, pagination: { page, pageSize, total } }`.
- Datas em ISO 8601, UTC. **A UI deve renderizar em `America/Sao_Paulo`** —
  uma votação às 21h de 15/06 em Brasília aparece como 16/06 se formatada em
  UTC, o que é data errada numa plataforma factual.
- Erros seguem `{ "error": { "code": "NOT_FOUND", "message": "..." } }` com o
  HTTP status correspondente (`400`, `404`, `429`, `500`).
- Toda entidade de matéria/votação carrega seu link de fonte oficial — nunca
  omitido, mesmo quando o dado já está cacheado.

### Cache e proteção contra abuso

Todas as respostas são públicas e idênticas para qualquer usuário, então o
cache de borda é a camada primária (ver
[ARQUITETURA.md § 7](ARQUITETURA.md#7-armazenamento-e-cache)):

```
Cache-Control: public, s-maxage=86400, stale-while-revalidate=604800, stale-if-error=604800
```

O TTL é de um dia porque o dado muda uma vez por dia — e porque, na volumetria
do piloto (~1.000 visitas/dia espalhadas por milhares de páginas), um TTL de
minutos não cacheia nada e o alvo de p95 na borda não seria atingido. A
contrapartida obrigatória é **invalidar a borda no rebuild**.

`stale-if-error` é o que mantém a plataforma respondendo — com conteúdo
levemente velho — quando o backend está fora do ar. Com single-AZ, é também a
mitigação da janela de manutenção do banco.

Limites validados **no servidor**, não apenas documentados:

| Parâmetro | Limite | Motivo |
|---|---|---|
| `pageSize` | máx. 100 | Evita varredura ampla por requisição |
| `q` | máx. 100 caracteres | Busca trigram é o endpoint mais caro |
| — | `statement_timeout` na credencial de leitura | Nenhuma consulta de usuário deve rodar por minutos |

Rate limiting por IP acontece na borda (WAF/Cloud Armor), antes de qualquer
compute. Requisições barradas recebem `429` no envelope de erro padrão.
