# Revisão de Arquitetura — VoteComDados

Revisão rigorosa da proposta descrita em [ARQUITETURA.md](ARQUITETURA.md),
[BACKEND.md](BACKEND.md), [FRONTEND.md](FRONTEND.md), [API.md](API.md) e
[`db/schema.sql`](../db/schema.sql), feita **antes de escrever código de
produção**.

> **Nota de honestidade intelectual:** a maior parte dos achados abaixo são
> defeitos da proposta que eu mesmo redigi nos documentos anteriores, não
> problemas herdados de terceiros. Vários são contradições internas entre
> documentos escritos em momentos diferentes. Isso é o resultado esperado de
> uma revisão feita a sério.

## Resumo executivo

| # | Achado | Pilar | Severidade | Status |
|---|---|---|---|---|
| B1 | CPF em texto claro no staging + hash reversível por força bruta | Segurança / LGPD | 🔴 Blocker | ✅ Corrigido |
| B2 | Health check agregado derruba a frota; cache falha fechado | Resiliência | 🔴 Blocker | ✅ Corrigido |
| B3 | Backfill N+1 contra API externa quando existem arquivos em massa | Escalabilidade | 🔴 Blocker | ✅ Corrigido |
| B4 | Perda silenciosa de votos por ordem de resolução de identidade | Domínio / Integridade | 🔴 Blocker | ✅ Corrigido |
| B5 | Normalização destrutiva do voto (rótulo de origem descartado) | Domínio / Produto | 🔴 Blocker | ✅ Corrigido |
| B6 | Worker sem exclusão mútua: watermark corrompível | Concorrência | 🔴 Blocker | ✅ Corrigido |
| B7 | API pública sem rate limiting nem WAF | Segurança | 🔴 Blocker | ✅ Corrigido |
| B8 | `AUSENTE` não tem fonte: a origem só lista quem votou | Domínio / Integridade | 🔴 Blocker | ✅ Corrigido |
| A4 | Dedup do staging não funciona com `id_externo` NULL | Integridade | 🟡 Aviso | ✅ Corrigido |
| A1–A14 | Ver [§ Pontos de Atenção](#-pontos-de-atenção-avisos) | vários | 🟡 | 12 resolvidos, 2 aceitos com gatilho, **nenhum aberto** |

O **B8** surgiu depois, ao verificar as fontes para responder à pergunta 10 —
não estava na revisão original. As respostas do owner em 30/08/2026 (ver
[§ Perguntas respondidas](#-perguntas-de-esclarecimento--respondidas)) fecham
**A1, A3, A5 e A7** e resolvem o **A2** por volumetria. As três mudanças de
schema que elas geraram (P1–P3) **foram aplicadas em 31/08/2026**, junto com a
remoção do Redis.

### O que foi corrigido e onde

Os 7 blockers (mais o A4, por estar na mesma restrição que o B4 tocou) foram
aplicados em [`db/schema.sql`](../db/schema.sql), [ARQUITETURA.md](ARQUITETURA.md),
[BACKEND.md](BACKEND.md), [API.md](API.md), [FRONTEND.md](FRONTEND.md) e nos
dois planos de custo.

**As garantias de schema estão cobertas por teste executável**, não apenas
documentadas: [`db/test_invariantes.sql`](../db/test_invariantes.sql) valida
**50 invariantes** contra um Postgres 16 real (político sem `nome_urna`,
`voto_origem` obrigatório, exclusão mútua por fonte, dedup com NULL, HMAC
dependente de chave, quarentena, e — desde as respostas de 31/08/2026 — voto
derivado, períodos de exercício sem sobreposição, curadoria com autor e data,
histórico de correção retroativa, a derivação de ausência/licença ponta a
ponta, o mapeamento contra amostra real da Câmara, a projeção de leitura e o
recorte de dados abertos sem dado pessoal). Todos passam, duas vezes seguidas
(`./db/validar.sh`).

**Bug adicional encontrado ao executar o DDL pela primeira vez:** `unaccent()` é
declarada `STABLE`, não `IMMUTABLE`, então o Postgres **rejeita** seu uso em
coluna `GENERATED` e em índice de expressão. O schema original — desde a primeira
versão — falharia na criação. Corrigido com um wrapper `unaccent_imutavel()` de
dicionário fixo. Nenhuma revisão de documento pegaria isso; só rodar pega.

**Decisões deixadas em aberto pela revisão** — remoção do Redis (A2),
pré-renderização diante da volumetria real (A1), multi-AZ e backup (A3),
auditoria da curadoria (A7) e tracing (A9) — foram todas decididas: as quatro
primeiras aplicadas, e o tracing **deliberadamente não adotado**, com gatilho
registrado.

---

## 🔴 Riscos Críticos (Blockers)

### B1. CPF em texto claro no staging, e `cpf_hash` reversível por força bruta

> **✅ Corrigido.** `cpf_hash` → `cpf_hmac` (HMAC-SHA256 com pepper), allowlist de redação obrigatória antes de staging e de log, coluna `campos_redigidos` para auditoria. Ver [ARQUITETURA.md § 10](ARQUITETURA.md#10-segurança-e-dados-pessoais).

**Dois defeitos compostos, ambos contradizendo o próprio documento.**

[ARQUITETURA.md § 10](ARQUITETURA.md#10-segurança-e-dados-pessoais) afirma:
*"Nada de CPF em claro. O CPF entra apenas como `cpf_hash`."*

Primeiro: **o staging bruto viola isso diretamente.** O dataset de
candidaturas do TSE (`consulta_cand`) contém o campo `NR_CPF_CANDIDATO`. Como
`staging.payload_bruto` grava o payload original *antes* de qualquer
normalização ([`db/schema.sql`](../db/schema.sql#L218)), o CPF de ~28 mil
candidatos fica persistido em claro, em JSONB, com retenção de 90 dias — em
um banco cuja credencial de escrita o worker carrega. A camada de staging que
eu adicionei para melhorar a operabilidade criou uma base de dados pessoais
que a arquitetura declara não existir.

Segundo: **`cpf_hash CHAR(64)` como SHA-256 puro não é anonimização.** O
espaço de CPFs válidos é de ~10¹⁰ com dígito verificador. Uma GPU comum
enumera esse espaço inteiro em segundos e monta uma rainbow table completa.
Um hash sem chave secreta é, na prática, uma representação reversível do
CPF — não um pseudônimo. A frase *"o identificador não precisa ser
reversível"* descreve a intenção, não o que o design entrega.

**Impacto:** exposição de dado pessoal de titulares identificáveis;
tratamento sem base legal clara; risco regulatório real em uma plataforma
cuja proposta de valor é credibilidade pública.

**Correção:**
1. Redigir campos sensíveis **antes** de gravar em staging (allowlist de
   campos por fonte, não denylist), ou excluir o TSE do staging bruto.
2. Trocar SHA-256 puro por **HMAC-SHA256 com pepper** guardado no gerenciador
   de segredos — a chave impede a enumeração offline.
3. Reavaliar se o CPF é necessário **depois** do casamento: uma vez resolvido
   o vínculo, `identificador_externo` já basta. Descartar o `cpf_hash` após a
   resolução elimina a categoria inteira de risco.
4. Adicionar mascaramento no appender de log (o worker loga payloads em erro).

---

### B2. O health check agregado derruba a frota inteira; o cache falha fechado

> **✅ Corrigido.** Grupos `liveness`/`readiness` separados, com o balanceador apontando para o *readiness*, que contém apenas o Postgres. Ver [ARQUITETURA.md § 9](ARQUITETURA.md#health-checks-o-que-pode-e-o-que-não-pode-derrubar-a-frota).
>
> **Atualização (31/08/2026):** a segunda metade deste achado — `@Cacheable` falhando fechado — deixou de existir com a **remoção do Redis** (ver Q7). O `CacheErrorHandler` de fail-open era configuração obrigatória para sobreviver à queda de um cache que, na volumetria definida, não entregaria acerto nenhum. A regra do health check continua valendo para toda dependência futura.

[BACKEND.md](BACKEND.md#observabilidade-e-operação) define
`/actuator/health` como health check do target group do ALB. Esse endpoint é
**agregado**: por padrão inclui os indicadores de Postgres *e* Redis.

A consequência é uma falha em cascata clássica: o Redis fica indisponível →
todos os containers reportam `DOWN` → o ALB os considera insalubres → tira
todos de rotação → **indisponibilidade total do site por causa da queda de um
cache**. Pior: o ECS/Cloud Run reinicia os containers em loop, e nenhum
consegue passar o health check enquanto o Redis não voltar.

Isso invalida diretamente a afirmação em
[ARQUITETURA.md § 9](ARQUITETURA.md#9-requisitos-não-funcionais): *"A queda
do Redis degrada latência, não disponibilidade."*

O segundo defeito reforça o primeiro: **o Spring Cache falha fechado por
padrão.** Se o Lettuce não alcança o Redis, `@Cacheable` propaga a exceção e
a requisição retorna erro 500 — o código nunca cai para o Postgres. Sem um
`CacheErrorHandler` explícito, o Redis é um SPOF de fato, apesar de descrito
como dependência lateral.

**Correção:**
1. Separar os grupos: `/actuator/health/liveness` (só o processo) e
   `/actuator/health/readiness` (só o Postgres — dependência sem a qual a
   API realmente não funciona). Apontar o load balancer para o *readiness*, e
   **nunca** incluir Redis em nenhum dos dois.
2. Registrar um `CacheErrorHandler` que loga e segue (fail-open), tornando o
   cache verdadeiramente opcional.
3. Adicionar teste de integração que sobe a aplicação **sem** Redis e exige
   resposta 200 — a garantia precisa ser verificada, não documentada.

---

### B3. O backfill é N+1 contra APIs de governo, quando existem arquivos em massa

> **✅ Corrigido.** Backfill por arquivos anuais em massa (ELT com `COPY`), REST só no incremental. `tema` passou a ter fonte e virou `proposicao_tema` (N:N). Worker: ~200h → ~15h nos dois planos de custo.

Este achado invalida a estimativa de esforço e o plano de custos do mês
piloto.

O protótipo `scripts/ingest-camara-proposicoes.ts` (removido do repositório ao
aplicar esta correção) paginava `/proposicoes` e então fazia **uma chamada de
detalhe por proposição**.
Somando o que o contrato da API exige (autores e temas por matéria), o
backfill de 10 anos tende a 3 chamadas por proposição, além de uma por
votação para obter os votos nominais — ordem de **centenas de milhares de
requisições HTTP** contra uma API pública que precisa ser tratada com rate
limit. É de onde vem a estimativa de "~200h de worker" no plano de custos: o
número não é otimista, é sintoma de um padrão de acesso equivocado.

**A Câmara publica os mesmos dados como arquivos anuais em massa.** Verificado
durante esta revisão:

| Arquivo (2023) | Tamanho | Resolve |
|---|---|---|
| `proposicoes-2023.csv` | 52 MB | Matérias + ementa + datas |
| `proposicoesAutores-2023.csv` | 42 MB | Autoria (hoje: 1 chamada/matéria) |
| `proposicoesTemas-2023.csv` | 2,7 MB | **Tema — que hoje não tem fonte alguma** |
| `votacoes-2023.csv` | 6,7 MB | Votações e placares |
| `votacoesVotos-2023.csv` | 43 MB | Votos nominais individuais |
| `deputados.csv` | 1,4 MB | Cadastro institucional |

Todos responderam HTTP 200, com `last-modified` do mesmo dia — ou seja, são
atualizados diariamente, não são um dump histórico congelado.

Isso reduz o backfill de ~500 mil requisições para **algumas dezenas de
downloads** (10 anos × 5 arquivos), de horas-a-dias para minutos, e elimina
quase toda a superfície de rate limiting, retry e circuit breaker no caminho
crítico da carga histórica.

**Achado adjacente, igualmente sério:** o campo `tema` aparece no schema
([`db/schema.sql`](../db/schema.sql#L129)), no contrato da API
([API.md](API.md)) e como filtro na UI ([FRONTEND.md](FRONTEND.md)) — mas
**nenhum passo do pipeline o popula.** O requisito "tema" do escopo original
está especificado ponta a ponta e não implementado em lugar nenhum. O arquivo
`proposicoesTemas` resolve isso.

**Correção:** inverter a estratégia — **arquivos em massa para carga
histórica (ELT: baixa → staging → transforma em SQL), API REST apenas para o
incremental diário.** Revisar as horas de worker no plano de custos depois.

---

### B4. Votos são descartados silenciosamente por ordem de resolução de identidade

> **✅ Corrigido.** `nome_urna` nulável, `nome_parlamentar` adicionado, `staging.registro_rejeitado` para quarentena, ordem de ingestão explícita e métrica de negócio com alerta.

`voto_nominal.politico_id` tem FK obrigatória para `politico`
([`db/schema.sql`](../db/schema.sql#L196)). Um voto só pode ser gravado se o
parlamentar já existir como `politico` **e** já tiver sido vinculado ao seu
`id_camara`.

O pipeline não define o que acontece quando essa condição falha, e ela
falhará rotineiramente:

- **Suplentes** que assumem no meio da legislatura votam em plenário, mas
  podem não estar no recorte de candidaturas carregado.
- Parlamentares eleitos em uma eleição **anterior** à janela de 10 anos de
  candidaturas ainda votam dentro da janela de votações.
- Matches fuzzy abaixo do threshold ficam pendentes de curadoria — e, por
  decisão explícita da própria arquitetura, não confirmados.

Nos três casos, o `INSERT` do voto viola a FK. Com `ON CONFLICT DO NOTHING`
ou try/catch por registro, o voto **desaparece sem rastro**. Uma plataforma de
transparência que omite silenciosamente votos de um parlamentar é pior que
inútil — é enganosa, e o erro é indetectável para o usuário.

Um defeito de modelagem relacionado: `politico.nome_urna` é `NOT NULL`
([`db/schema.sql`](../db/schema.sql#L37)), mas um parlamentar conhecido apenas
pelo registro institucional da Câmara/Senado **não tem nome de urna** — esse
campo é do domínio eleitoral do TSE. O schema torna impossível representar a
entidade que a ingestão de votos precisa criar primeiro.

**Correção:**
1. Tornar `nome_urna` nulável; `politico` passa a ser criável a partir do
   registro institucional, com `candidatura` como associação opcional.
2. Definir ordem explícita de ingestão: cadastro de parlamentares → votações
   → votos, com o cadastro criando `politico` quando ausente.
3. **Quarentena em vez de descarte:** tabela `staging.registro_rejeitado`
   com o payload, o motivo e a execução. Um voto que não casou é um item de
   trabalho visível, não um silêncio.
4. Métrica de negócio exposta: nº de votos em quarentena por execução, com
   alerta. Zero é o estado esperado.

---

### B5. A normalização do voto descarta o rótulo original

> **✅ Corrigido.** `voto_origem` obrigatório em `voto_nominal`, tabela `mapeamento_voto` versionada como dado, `votoOrigem` + `notaMetodologica` no contrato da API e exibição obrigatória no `VotoBadge`.

`tipo_voto_enum` fixa seis valores (`SIM`, `NAO`, `ABSTENCAO`, `AUSENTE`,
`OBSTRUCAO`, `ART_17`) e o pipeline mapeia as strings de origem para eles
([`db/schema.sql`](../db/schema.sql#L23)). O valor original **não é
preservado em nenhum lugar** do schema curado.

Câmara e Senado não usam o mesmo vocabulário nem a mesma semântica. "Obstrução"
é uma manobra regimental de orientação de bancada; "Art. 17" é o presidente da
Casa que não vota; "ausente" pode significar licença médica, missão oficial ou
falta. Colapsar isso em um enum é uma decisão **editorial** disfarçada de
detalhe técnico — exatamente o que o requisito de neutralidade proíbe.

E é irreversível: descoberto um erro de mapeamento seis meses depois, não há
como recomputar a partir do dado curado. (O staging bruto mitigaria, mas
apenas dentro da retenção de 90 dias.)

**Impacto:** risco de atribuir a um parlamentar uma conduta que ele não teve.
Para este produto, é o pior modo de falha possível — o único que destrói a
premissa do projeto.

**Correção:**
1. Adicionar `voto_origem TEXT NOT NULL` com a string literal da fonte, ao
   lado do enum normalizado.
2. Versionar a tabela de mapeamento (origem → enum) como dado, não como
   `switch` em código, com revisão explícita de quem entende de processo
   legislativo.
3. Exibir na UI o rótulo oficial junto do normalizado, com nota de
   metodologia — e nunca apresentar `AUSENTE` como se fosse uma escolha
   política deliberada.

---

### B6. O worker não tem exclusão mútua: o watermark é corrompível

> **✅ Corrigido.** Advisory lock por fonte + índice único parcial `WHERE status = 'EM_ANDAMENTO'` + reaper de execuções órfãs; watermark gravado com `GREATEST`.

`ingestao_execucao` não impede duas execuções simultâneas para a mesma fonte —
nada restringe múltiplas linhas com `status = 'EM_ANDAMENTO'`.

Cenários realistas: o job diário demora mais de 24h (provável durante o
backfill, pelo problema B3) e o scheduler dispara o seguinte; um operador roda
um backfill manual enquanto o incremental está ativo; o ECS/Cloud Run
reexecuta uma task após falha de health check sem que a anterior tenha morrido.

O resultado é grave porque o watermark é o mecanismo de correção do sistema:
duas execuções concorrentes leem o mesmo watermark inicial, processam a mesma
janela e a que terminar por último grava seu `watermark_novo` — que pode ser
**anterior** ao da outra. Nesse caso a janela entre os dois marcadores é
reprocessada indefinidamente ou pulada, e a idempotência dos upserts não
protege contra isso: o dado não fica duplicado, fica **faltando**.

**Correção:**
1. `pg_try_advisory_lock(hashtext('ingestao:' || fonte))` no início do job;
   se não obtiver, encerrar com log — não enfileirar.
2. Índice único parcial como rede de segurança:
   `CREATE UNIQUE INDEX ON ingestao_execucao (fonte) WHERE status = 'EM_ANDAMENTO'`.
3. Timeout de execução e reaper para linhas `EM_ANDAMENTO` órfãs (processo
   morto por OOM/evicção nunca marca `FALHA` — o lock atual ficaria preso).
4. Avançar o watermark com `GREATEST(watermark_novo, valor_atual)`, nunca
   retroceder.

---

### B7. API pública sem rate limiting, sem WAF e sem cache de borda

> **✅ Corrigido.** WAF/Cloud Armor com rate limit por IP (linha nova nos dois planos de custo), `Cache-Control` público na borda com `stale-if-error`, `statement_timeout` e limites de `q`/`pageSize` validados no servidor.

A API é pública, anônima e somente leitura — e não há nenhum controle de
abuso documentado em nenhum dos documentos.

O endpoint mais barato de atacar é o mais caro de servir:
`GET /politicos?q=...` executa busca trigram/`tsvector` sobre `politico`, com
`COUNT(*)` para a paginação, em uma instância **burstable** de banco
(`db.t4g.micro` / `db-f1-micro`). Um único cliente variando `q` gera chaves de
cache sempre distintas — **contorna o Redis inteiro por construção** — e vai
direto ao Postgres. Poucas centenas de requisições por segundo esgotam os
créditos de CPU do banco e derrubam o site.

Agravante de contexto: uma plataforma que expõe o comportamento de votação de
parlamentares é um alvo plausível de ataque politicamente motivado, sobretudo
em período eleitoral. Isso não é ameaça hipotética de livro-texto.

**Correção:**
1. Rate limiting na borda (AWS WAF rate-based rule / Cloud Armor), por IP,
   antes de qualquer compute.
2. `Cache-Control` público nas respostas da API + cache de borda na CDN — ver
   recomendação R2, que resolve custo e resiliência juntos.
3. Limite duro de `pageSize` (já em API.md) **validado no servidor**, e
   comprimento máximo de `q`.
4. Timeout de statement no Postgres (`statement_timeout`) para a credencial
   de leitura: nenhuma consulta de usuário deve poder rodar por minutos.

---

### B8. `AUSENTE` está especificado ponta a ponta e não tem fonte

> **✅ Corrigido.** Achado em 30/08/2026, ao verificar a fonte para responder à
> pergunta 10 — não estava na revisão original. `mandato_exercicio`,
> `LICENCIADO` e `origem_registro` aplicados em
> [`db/schema.sql`](../db/schema.sql), com a derivação coberta ponta a ponta
> pelos invariantes T25–T31 e T39.

`tipo_voto_enum` inclui `AUSENTE`; o contrato da API o devolve; o `VotoBadge`
sabe pintá-lo. **Nenhuma linha de nenhuma fonte produz esse valor.**

Verificado em `votacoesVotos-2026.csv`: existem exatamente cinco rótulos no ano
— `Sim` (32.020), `Não` (18.312), `Abstenção` (127), `Artigo 17` (87) e
`Obstrução` (35). Não há "ausente". A mediana é de **398 linhas por votação
nominal, para 513 cadeiras**: os ~115 parlamentares restantes simplesmente não
aparecem no arquivo.

É o mesmo defeito do campo `tema` no B3 — especificado em schema, contrato e UI,
sem nenhum passo do pipeline que o popule — mas com consequência pior. Aqui o
resultado não é um campo vazio: é uma **lista de votos que omite as ausências em
silêncio**, exatamente o modo de falha que o B4 existe para impedir. Um
parlamentar que faltou a 40% das votações apareceria com um histórico
aparentemente limpo.

A ausência não é um dado que se ingere; é um dado que se **calcula**, cruzando a
votação com a lista de quem estava em exercício naquela data. E, uma vez que se
tem essa lista, distinguir `LICENCIADO` de `AUSENTE` sai de graça — que é o que
a pergunta 10 pedia.

**Correção aplicada:** tabela `mandato_exercicio` (com `EXCLUDE` que proíbe
períodos sobrepostos, porque sobreposição produziria `AUSENTE` onde havia
licença), `mapeamento_situacao` versionada como dado à imagem do
`mapeamento_voto`, e `origem_registro` (`FONTE` | `DERIVADO`) com `CHECK` que
mantém a garantia do B5 intacta: linha derivada não finge ter origem, e voto
declarado nunca perde o rótulo. O T39 exercita a derivação inteira — licenciado
não vira ausente, e suplente não empossado não vira linha nenhuma.

---

## 🟡 Pontos de Atenção (Avisos)

**A1. Volumetria do frontend estático está subdimensionada em ~50×.**
*(✅ Resolvido: pré-renderiza só `possui_atuacao_legislativa` — ordem de 2 a 4 mil
páginas, não 28 mil. Ver Q1.)*
[FRONTEND.md](FRONTEND.md) assume gerar uma página por político via
`generateStaticParams`. O escopo é "candidatos a cargos federais": o TSE
registrou ~28 mil candidaturas a Deputado Federal em 2022, contra 594
parlamentares em exercício. A diferença entre gerar 600 e 28.000 páginas
estáticas é a diferença entre um build de 1 minuto e um de 30+ — executado
**diariamente** após cada ingestão. Decidir: pré-renderizar apenas quem tem
mandato/atuação e servir o restante via rota dinâmica no cliente.

**A2. O Redis provavelmente é desnecessário — e o cache de borda é melhor.**
*(✅ Resolvido em 31/08/2026: Redis removido. Ver Q7.)*
100% das respostas são públicas e idênticas para todos os usuários. Esse é o
caso ideal de cache HTTP na CDN, que responde na borda sem tocar em compute,
enquanto o Redis exige uma requisição chegar ao container para depois evitar o
banco. Manter o Redis custa US$ 19–21/mês, adiciona um SPOF (B2) e uma
dependência de runtime, para entregar menos que um header
`Cache-Control: public, s-maxage=300, stale-while-revalidate=600`.

**A3. Banco single-AZ, burstable, sem backup documentado.**
*(✅ Resolvido: single-AZ assumido com RPO 5 min / RTO 4 h, backup e teste de
restore orçados. Ver Q5.)*
Três lacunas
distintas: (a) single-AZ significa que uma janela de manutenção do provedor é
indisponibilidade total; (b) instâncias burstable acumulam/gastam créditos de
CPU — um backfill de 200h os esgota e o banco entra em throttle permanente;
(c) nenhum documento menciona retenção de backup, PITR, RPO ou teste de
restore. "Reprocessar a ingestão" não é plano de recuperação quando a
ingestão leva dias.

**A4. O dedup do staging não funciona para registros sem `id_externo`.**
`UNIQUE (fonte, recurso, id_externo, payload_hash)` com `id_externo` nulável
([`db/schema.sql`](../db/schema.sql#L224)): no Postgres, `NULL` nunca é igual
a `NULL`, então a restrição **não deduplica nada** quando o campo é nulo.
Cada execução insere novamente os mesmos registros e o staging cresce sem
limite. Usar `NULLS NOT DISTINCT` (PG 15+) ou um sentinela não nulo.

**A5. O upsert de `proposicao` nunca corrige `ementa` nem `tema`.**
*(✅ Metade de schema resolvida em 31/08/2026: `proposicao_historico` + trigger
guardam a versão anterior, para que atualizar não vire perder. A outra metade —
o upsert passar a atualizar o campo — é do worker, ainda não escrito.)* O
`DO UPDATE SET` do protótipo atualiza apenas `situacao_atual` e
`url_inteiro_teor`. Se a Câmara corrigir uma ementa, a plataforma exibe a
versão errada para sempre.

**A6. Paginação por OFFSET com `COUNT(*)` em busca textual.**
*(🟨 Risco aceito em 31/08/2026, com gatilho: a ~1.000 visitas/dia o custo é
irrelevante, e trocar por keyset agora seria otimizar sem medida. Revisitar se
o p95 da busca passar de 500ms, ou se `politico` crescer além da coorte.)*
O contrato
devolve `total` em toda página; isso exige uma segunda consulta de contagem,
que no Postgres não tem atalho e piora com o crescimento da tabela. Além
disso, OFFSET sobre resultado ordenado por relevância é instável entre
páginas. Considerar keyset pagination e contagem aproximada/cacheada.

**A7. Curadoria manual sem trilha de auditoria.**
*(✅ Resolvido em 31/08/2026: `revisado_por` e `revisado_em` em
`identificador_externo`, com `CHECK` que impede marcar como revisado sem dizer
quem e quando. Invariantes T32/T33.)* `revisado_manualmente` é
um booleano — não há `revisado_por` nem `revisado_em`. A arquitetura afirma
que toda decisão de casamento é auditável, mas a decisão *humana*, que é
justamente a discricionária, não registra autor nem data. Somado a `UPDATE`
manual em produção sem revisão, é a maior lacuna de governança do design.

**A8. O webhook de rebuild não tem garantia de entrega.**
*(✅ Resolvido em 31/08/2026 pela alternativa (b) do R3: o CI compara o
watermark de `/meta/status` com o `build-info.json` publicado na CDN e
reconstrói se houver diferença. Não há mensagem para se perder, e uma execução
que falhe é corrigida pela seguinte. Ver
[FRONTEND.md](FRONTEND.md#pipeline-de-rebuild).)*
O worker chamava o CI ao concluir. Se a chamada falhasse (deploy do CI, rede,
token expirado), o site estático **nunca seria reconstruído** e ninguém
perceberia — a falha era silenciosa e o dado novo ficava invisível.

**A9. Observabilidade incompleta para o pilar que mais importa aqui.**
*(🟨 Parcialmente resolvido. Métricas de negócio e mascaramento de log estão
especificados (B1, B4, Q12); **tracing distribuído fica deliberadamente de
fora**: com um serviço, um job e um operador, correlacionar spans resolve um
problema que não temos, e o custo operacional não é zero. Gatilho: um terceiro
artefato, ou primeira investigação em que o log estruturado não bastar.)*
Há
logs estruturados e métricas de sistema, mas falta: tracing distribuído
(OpenTelemetry) correlacionando execução do worker → fonte → registro;
mascaramento de dados sensíveis no log (ver B1); e **métricas de negócio**,
que neste domínio são as que realmente indicam saúde — cobertura de vínculos
resolvidos, nº de matches pendentes de curadoria, votos em quarentena,
defasagem por fonte. Uptime verde com 30% dos votos em quarentena é um
sistema falhando silenciosamente.

**A10. Pool de conexões vs. escalonamento horizontal.**
*(✅ Resolvido: `maximum-pool-size` dimensionado explicitamente pelo teto do
banco em `application.yml`, com a nota do porquê. pgBouncer não se justifica
nesta volumetria.)*
`db-f1-micro` no
Cloud SQL suporta poucas dezenas de conexões; Cloud Run escala por instância e
cada uma abre seu próprio pool HikariCP. Somado ao worker, o teto é atingido
antes de a CPU ser gargalo. Dimensionar `maximum-pool-size` explicitamente em
função do máximo de instâncias, ou adotar pgBouncer. (`db-f1-micro` também é
shared-core, não recomendado para produção pelo próprio Google.)

**A11. Fuso horário.**
*(✅ Resolvido: `America/Sao_Paulo` fixado em `web/src/lib/formato.ts` e exigido
no contrato em API.md.)*
Votações ocorrem em horário de Brasília; a API devolve
UTC. Uma votação às 21h de 15/06 aparece como 16/06 se o cliente renderizar em
UTC — data errada em uma plataforma factual. Fixar `America/Sao_Paulo` na
formatação e testar a virada de dia.

**A12. Risco de integração com o Senado não foi avaliado.**
*(✅ **Spike executado e concluído em 31/08/2026.** O maior desconhecido técnico
do projeto deixou de ser desconhecido. Resumo: o endpoint que estes documentos
presumiam está **desativado desde 01/02/2026**; o substituto tem OpenAPI,
cobre **desde 1991** (dez anos a mais que a Câmara), publica **a bancada
inteira por votação** — o Senado dispensa a derivação do B8 — e o backfill
inteiro cabe em ~35 chamadas. Em troca, traz 13 rótulos que não cabem no
`tipo_voto_enum`, **53% das votações são secretas**, e o CSV da API descarta os
votos em silêncio. O spike também expôs um defeito estrutural que a revisão não
tinha visto: `cobertura_fonte` não sabe representar duas Casas na mesma esfera.
Detalhe em [PLANO_IMPLEMENTACAO.md § 1](PLANO_IMPLEMENTACAO.md#1-a12--o-senado).)*
Os endpoints
foram assumidos a partir do enunciado. A API do Senado tem convenções bem
diferentes da Câmara (XML/JSON, paginação inconsistente, endpoints legados) e
não há garantia de arquivos em massa equivalentes. Precisa de um *spike*
antes do planejamento — é o maior desconhecido técnico do projeto.

**A13. `jOOQ` codegen exige Docker no CI.**
*(✅ Resolvido por decisão: o projeto adotou `JdbcClient` com SQL à mão, pelo
motivo que este aviso antecipou. O preço — perder verificação em tempo de
compilação — é pago pelos testes de integração. Ver BACKEND.md.)*
Gerar código a partir de um
Postgres com Flyway aplicado via Testcontainers implica runner com Docker
disponível e adiciona minutos a cada build. Alternativa: gerar em passo
separado e versionar as classes.

**A14. Kubernetes: recomendo explicitamente *não* adotar.**
*(✅ Aceito: ECS/Cloud Run, com as preocupações de ciclo de vida atendidas pelo
R6. Reforçado pela Q12 — não há operador para pagar o custo cognitivo.)*
A revisão foi
pedida com foco em ciclo de vida de pods, probes, requests/limits e evicções —
mas **o design atual não usa Kubernetes**, e introduzi-lo aqui seria um erro:
o control plane do EKS custa ~US$ 73/mês e o GKE equivalente é comparável,
sobre um orçamento total de US$ 120–170/mês — antes de qualquer nó. Para dois
artefatos (uma API e um job em cron), K8s adiciona uma superfície operacional
que não se paga. As preocupações legítimas por trás do pilar continuam
válidas e mapeiam para ECS/Cloud Run — ver R6.

---

## 🟢 Pontos Fortes

- **Idempotência por chave natural em toda escrita** (`ON CONFLICT` sobre
  `(casa, id_externo)` e equivalentes). É a decisão que torna todo o resto
  recuperável: reprocessar é sempre seguro, o que elimina a necessidade de
  modo dry-run e simplifica a resposta a falhas.
- **Watermark que só avança em sucesso.** Corretamente identificado como o
  ponto onde um erro causaria perda silenciosa de dados, e resolvido na
  direção certa (reprocessar é barato; perder janela é inaceitável).
- **API estritamente somente leitura, com credencial de banco só de
  `SELECT`.** Reduz drasticamente o impacto de um comprometimento da
  superfície pública e simplifica o raciocínio sobre cache e concorrência.
- **Separação em dois artefatos com ciclos de vida distintos** (serviço 24/7
  vs. job efêmero), alinhada ao modelo de cobrança — decisão de arquitetura e
  de custo tomada em conjunto, não em sequência.
- **Resolução de identidade com viés para omitir.** Preferir mostrar dado
  incompleto a afirmar vínculo incerto é a escolha certa para o domínio, e
  está registrada com threshold auditável.
- **Staging bruto** é a decisão certa pelo motivo certo (bugs de normalização
  são descobertos tarde), apesar do problema de PII em B1 — que é de
  implementação, não de conceito.
- **Frontend estático** entrega o alvo de TTFB praticamente de graça e remove
  compute do caminho crítico.
- **Tratamento explícito de votação simbólica** e link para a fonte oficial em
  cada registro: requisito de neutralidade traduzido em schema e contrato de
  API, não apenas em texto de UI.
- **Decisões documentadas com gatilho de revisão explícito.** Raro e valioso:
  a tabela de decisões diz não só o que foi escolhido, mas o que precisa
  acontecer para reabrir a discussão.
- **C4 neutro de provedor com tabela de mapeamento**, preservando a decisão
  AWS/GCP em aberto sem duplicar diagramas.

---

## 🛠️ Recomendações Práticas

**Decididas pelo owner em 31/08/2026.** Sete aceitas, uma adiada:

| # | Recomendação | Decisão | Onde está |
|---|---|---|---|
| R1 | ELT sobre arquivos em massa, REST só no incremental | ✅ Aceita — já aplicada no B3 | [ARQUITETURA.md § 5](ARQUITETURA.md#arquivos-em-massa-para-carga-histórica-rest-apenas-para-o-incremental) |
| R2 | Cache de borda como camada primária | ✅ Aceita — Redis removido (Q7) | [ARQUITETURA.md § 7](ARQUITETURA.md#por-que-não-há-cache-in-memory-atrás-da-api) |
| R3 | Gatilho de rebuild com garantia de entrega | ✅ Aceita, **alternativa (b)**: CI compara watermark, sem broker | [FRONTEND.md](FRONTEND.md#pipeline-de-rebuild) |
| R4 | CQRS leve: projeção de leitura do perfil | ✅ Aceita — `perfil_leitura` + invariantes T42–T47 | [`db/schema.sql`](../db/schema.sql) |
| R5 | Quarentena explícita com métrica de negócio | ✅ Aceita — já aplicada no B4, refinada em Q3 | [ARQUITETURA.md § 5](ARQUITETURA.md#quarentena-falhar-visível-em-vez-de-descartar-em-silêncio) |
| R6 | Ciclo de vida de container sem Kubernetes | ✅ Aceita — config aplicada | [BACKEND.md](BACKEND.md#health-checks-e-ciclo-de-vida-do-container) |
| R7 | Testes com dados reais como *golden files* | ✅ Aceita — amostra da Câmara + T40/T41 | [`db/golden/`](../db/golden/) |
| R8 | Publicar os dados consolidados como dados abertos | ✅ Aceita — schema `dados_abertos` + T48–T50 | [ARQUITETURA.md § 8b](ARQUITETURA.md#8b-dados-abertos-devolver-o-dado-consolidado) |

As oito foram aceitas. O R8 chegou a ser adiado e voltou no mesmo dia, o que
foi a decisão certa: ele é o item mais alinhado à missão do projeto — permite
auditoria externa justamente da parte que mais pede confiança, o cruzamento.

O que ele custa não é infraestrutura (alguns CSVs no object storage já
orçado), é **exposição**: o manifesto publica quantos vínculos vieram de
similaridade e quantos ainda não passaram por revisão humana. Um pacote de
dados abertos sem esse número seria peça de marketing, não instrumento de
auditoria.


**R1. Inverter a ingestão: ELT sobre arquivos em massa, REST só no
incremental.** Baixar CSV anual → carregar em `staging` com `COPY` →
transformar em SQL dentro do banco. Elimina o N+1 (B3), remove rate
limiting/retry do caminho crítico da carga histórica, e torna o reprocesso
completo viável em minutos. A API REST fica reservada ao delta diário, onde o
volume é pequeno e o padrão request/response é adequado.

**R2. Cache HTTP na borda como cache primário; Redis como otimização
opcional (ou removido).** `Cache-Control: public, s-maxage=300,
stale-while-revalidate=600, stale-if-error=86400` na API resolve três
problemas de uma vez: colapsa a maior parte do tráfego antes do compute
(custo), sobrevive a uma queda do backend servindo conteúdo levemente velho
(`stale-if-error` — resiliência), e remove o SPOF do B2. Para dados públicos
idênticos para todos os usuários, é estritamente superior a um cache
in-memory atrás do compute.

**R3. Outbox Pattern (ou reconciliação idempotente) para o gatilho de
rebuild.** O worker hoje faz "grava no banco, depois chama o webhook" — duas
operações sem atomicidade, e a segunda pode se perder (A8). Duas saídas: (a)
Outbox clássico — gravar o evento na mesma transação e um processo separado o
publica com retry até sucesso; ou (b) mais simples e provavelmente melhor
aqui: **o CI roda por cron e compara o watermark com o último build**,
reconstruindo se houver diferença. Elimina o acoplamento worker→CI e é
naturalmente idempotente. Prefira (b) e evite o broker.

> **✅ Escolhida a (b) em 31/08/2026.** Estado do último build em
> `build-info.json`, publicado ao lado do site na própria CDN — sem banco de
> controle de CI e sem estado escondido no runner. Detalhe em
> [FRONTEND.md](FRONTEND.md#pipeline-de-rebuild).

**R4. CQRS leve: tabela de leitura desnormalizada para o perfil.** A página de
perfil hoje exige múltiplas consultas (político, candidaturas, contagens).
Materializar uma tabela `perfil_leitura`, reconstruída ao fim de cada
ingestão, colapsa isso em um `SELECT` por chave primária. O sistema já é
CQRS de fato — escrita exclusiva do worker, leitura exclusiva da API — então o
padrão se encaixa sem introduzir consistência eventual nova: a defasagem já é
diária e já está exposta em `/meta/status`.

**R5. Quarentena explícita em vez de descarte, com métrica de negócio.**
`staging.registro_rejeitado` (payload, motivo, execução) + contador por
execução + alerta em qualquer valor diferente de zero. Resolve B4 e transforma
a classe inteira de "falha silenciosa" em trabalho visível.

**R6. As preocupações de ciclo de vida de container, sem Kubernetes.** O que o
pilar de orquestração pede, traduzido para ECS/Cloud Run:
- **Graceful shutdown:** `server.shutdown=graceful` +
  `spring.lifecycle.timeout-per-shutdown-phase=25s`, com o `stopTimeout` da
  task **maior** que esse valor e o *deregistration delay* do ALB alinhado —
  caso contrário conexões em voo são cortadas em todo deploy.
- **Probes:** grupos separados de liveness/readiness, sem Redis (B2). Liveness
  que verifica dependência externa causa reinício em loop.
- **Requests/limits:** definir CPU/memória explicitamente e fixar
  `-XX:MaxRAMPercentage=75` — a JVM ignora limites de container se não for
  configurada, e é morta por OOM antes de fazer GC.
- **Resiliência a evicção:** o worker precisa sobreviver a ser morto no meio
  (Fargate Spot, revisão de Cloud Run). Já é atendido por idempotência +
  watermark, mas depende do reaper de execuções órfãs do B6.
- **Se houver mandato organizacional de K8s:** GKE Autopilot (cobra por pod,
  sem gerenciar nós), com `PodDisruptionBudget`, `terminationGracePeriodSeconds`
  coerente com o shutdown do Spring, e `preStop` de alguns segundos para
  drenar o Endpoints antes do SIGTERM.

**R7. Testes com dados reais como *golden files*.** Fixar amostras dos CSVs
oficiais no repositório e testar o mapeamento de voto contra elas. O maior
risco do produto (B5) é de correção de domínio, não de código — e só testes
com dado real o detectam.

**R8. Publicar os próprios dados consolidados como dados abertos.** Um dump
periódico do schema curado, versionado e com metodologia documentada. É
coerente com a missão, permite auditoria externa do cruzamento (a parte em que
a plataforma mais pede confiança) e é barato: um arquivo no object storage.

> **✅ Aplicada em 31/08/2026.** Schema `dados_abertos` (views), exportação no
> worker (`ExportadorDeDadosAbertos`), instantâneos datados imutáveis e
> manifesto que declara a fila de curadoria pendente. Detalhe em
> [ARQUITETURA.md § 8b](ARQUITETURA.md#8b-dados-abertos-devolver-o-dado-consolidado).

---

## ✅ Perguntas de Esclarecimento — respondidas

Respondidas pelo owner em **30/08/2026**; as mudanças de schema que elas
geraram foram aplicadas em **31/08/2026**. Cada resposta vira decisão
registrada aqui, com o invariante que a sustenta.

*Nomenclatura: **Q1–Q13** são as respostas às perguntas desta seção; **R1–R8**
continuam sendo as [recomendações práticas](#-recomendações-práticas);
**P1–P3**, as pendências que estas respostas geraram.*

### Fatos verificados nas fontes ao processar as respostas

Quatro respostas apoiavam-se em premissas sobre as fontes. As premissas foram
checadas contra os dados reais em 30/08/2026, não presumidas:

| Verificação | Resultado | Afeta |
|---|---|---|
| `votacoes-2026.csv`, `votacoesVotos-2026.csv` | HTTP 200, `last-modified` do próprio dia | Q6 |
| Votações de plenário **depois de maio/2026** | **308** (jun 107, jul 128, ago 73) | **Q6** |
| Rótulos de voto em 2026 | 5: `Sim`, `Não`, `Abstenção`, `Artigo 17`, `Obstrução` — **nenhum "ausente"** | **Q10** |
| Linhas por votação nominal | mediana **398** de 513 cadeiras; 149 votações nominais no ano | **Q10** |
| `cpf` em `deputados.csv` | a coluna existe e está **vazia nas 7.889 linhas** | Q11 |
| `nomeCivil` + `dataNascimento` em `deputados.csv` | 100% preenchidos da legislatura 54 (2011) em diante | Q8, Q11 |
| `/deputados/{id}/historico` | expõe `situacao` (`Exercício`, `Licença`, `SUPLENCIA`, `CONVOCADO`, `FIM_MANDATO`) e `condicaoEleitoral` (`Titular`/`Suplente`) | Q3, Q10 |

Duas dessas verificações mudam decisões: a premissa da Q6 não se sustenta, e a
Q10 revelou que `AUSENTE` não tem fonte (novo blocker **B8**).

### Volumetria e escopo

**Q1. Candidatos, não parlamentares — a coorte de 2026 é o escopo.**
✅ *Já aplicado.* É o modelo descrito em
[ARQUITETURA.md § 5](ARQUITETURA.md#a-coorte-de-2026-define-o-escopo): `politico`
só existe para quem tem candidatura em 2026; quem não é candidato não tem
registro pessoal.

**Fecha o A1 com número.** Pré-renderiza-se apenas
`possui_atuacao_legislativa`. O universo de deputados federais com mandato de
2011 em diante é de **1.566 pessoas** (contagem em `deputados.csv`), e só uma
fração é candidata em 2026 — somando o Senado e os mandatos de 2001–2010, o
build estático fica na ordem de **2 a 4 mil páginas**, não 28 mil. O restante
da coorte responde "sem mandato legislativo anterior" por rota dinâmica.

**Q2. Todos os mandatos, em qualquer cargo — sem janela temporal.**
✅ *Já aplicado.* O limite passa a ser a fonte, não a política de recorte, e o
teto de cada fonte já está verificado e documentado: trajetória eleitoral pelo
TSE nos três níveis desde 1933; autoria federal desde 1934; **voto nominal
federal só desde 2001**; Alesp só voto de comissão; municipal, nada.

A consequência de produto é que "todos os mandatos" se cumpre integralmente na
*trajetória eleitoral* e apenas parcialmente na *atuação legislativa* — é
exatamente o que `cobertura_fonte` existe para comunicar, com três mensagens
distintas em vez de um silêncio.

**Q3. Suplentes e quem assumiu fora de eleição entram.**
✅ *Parcialmente aplicado* — `nome_urna` já é nulável e a quarentena já existe
(B4). A verificação acrescenta a fonte que faltava: `/deputados/{id}/historico`
distingue `condicaoEleitoral` `Titular`/`Suplente` e marca `CONVOCADO`, então
não é preciso inferir a posse de um suplente por heurística.

✅ **Aplicado em 31/08/2026** (era um problema real do desenho): `motivo_rejeicao_enum`
tem `POLITICO_NAO_RESOLVIDO`, mas não distingue **"não é da coorte"** de
**"deveria ter casado e não casou"**. Uma votação nominal traz ~398 linhas, e a
maioria é de parlamentares que não são candidatos em 2026 — cair na mesma
quarentena colocaria **dezenas de milhares de linhas por execução** numa métrica
cujo valor esperado é zero, e o alerta do B4 (recomendação R5) nasceria morto.
`FORA_DA_COORTE` é disposição própria, gravada uma vez por parlamentar, contada
e nunca alertada; um índice único parcial impede que reprocessar multiplique as
linhas (T36/T37).

### SLA e latência

**Q4. Os alvos são p95:** TTFB < 200ms em rota cacheada, < 500ms em consulta
direta ao Postgres. Medido na borda, não no container.

⚠️ **Consequência não óbvia, e ela contradiz o desenho de cache atual.** Com a
volumetria da Q7 (~1.000 visitas/dia distribuídas por milhares de páginas), um
`s-maxage=300` não cacheia praticamente nada: a chance de duas visitas à mesma
página caírem na mesma janela de 5 minutos é desprezível. **Quase toda
requisição seria um miss**, e o p95 real seria o da consulta ao banco.

Como o dado muda no máximo uma vez por dia, o TTL deve acompanhar o dado, não a
paranoia: `s-maxage=86400, stale-while-revalidate=604800, stale-if-error=604800`,
com invalidação explícita no rebuild. Aí o p95 de 200ms passa a ser atingível de
fato — hoje ele não seria.

**✅ Confirmado pelo owner em 31/08/2026: o TTL acompanha o dado.** Aplicado em
[ARQUITETURA.md § 7 e § 9](ARQUITETURA.md#7-armazenamento-e-cache) e no contrato
em [API.md](API.md#cache-e-proteção-contra-abuso). A contrapartida é
obrigatória e não pode ser esquecida na implantação: **invalidar a borda a cada
rebuild** — TTL longo sem invalidação exibiria dado velho por tempo
perceptível.

**Q5. Single-AZ, com backup e restore.** Alvos que decorrem da escolha:

| | Valor | Como |
|---|---|---|
| **RPO** | 5 min | PITR do backup automático gerenciado |
| **RTO** | 4 h | restore de snapshot em instância nova |
| **RTO catastrófico** | ~1 dia útil | reingestão completa a partir dos CSVs arquivados no object storage |
| Retenção | 7 dias de PITR + snapshot mensal | — |
| Teste de restore | antes do lançamento e a cada trimestre | restore não testado não é backup |

O que single-AZ custa, dito explicitamente: **a janela de manutenção do
provedor é indisponibilidade da API**. Isso é aceitável aqui só porque o shell
do perfil é estático e o `stale-if-error` da Q4 serve conteúdo levemente velho
— o site continua de pé, as abas dinâmicas é que degradam. Fecha o A3.

**Q6. Defasagem — ⚠️ a premissa da resposta não se confirma nos dados.**

A resposta foi "não há necessidade de atualização, porque candidatos não podem
participar de projetos nos 6 meses anteriores à eleição; não deve haver
alteração a partir de maio de 2026".

A regra dos 6 meses (desincompatibilização, art. 14 § 6º da Constituição e
LC 64/1990) alcança chefes do Executivo que disputam outro cargo. **Não alcança
deputados e senadores**, que mantêm o mandato e continuam votando até
31/01/2027, inclusive quando são candidatos. Os dados confirmam:

> Em `votacoes-2026.csv`, baixado hoje: **308 votações de plenário depois de
> maio/2026** — 107 em junho, 128 em julho, 73 em agosto. O arquivo tem
> `last-modified` de hoje de manhã.

Congelar a ingestão em maio esconderia justamente os votos dados **durante a
campanha**, que é quando o eleitor mais tem motivo para consultá-los. Para uma
plataforma cujo nome é "vote com dados", é o pior mês possível para parar.

**Decisão, ✅ confirmada pelo owner em 31/08/2026:** manter a ingestão
incremental diária até o fim da legislatura (31/01/2027), **sempre que
possível**. O custo é desprezível — os arquivos em massa já são baixados
inteiros e o job leva minutos.

A expressão "sempre que possível" é a precisão que importa, e ela é a diferença
entre um alvo e um compromisso: a ingestão diária é **melhor esforço**, não SLA
contratado. D+1 sem urgência, sem alerta de madrugada, e uma falha de um dia se
recupera sozinha no dia seguinte, porque o watermark só avança em sucesso e os
upserts são idempotentes. O alerta só dispara na **segunda falha consecutiva**
(§ 9 de ARQUITETURA.md) — um dia perdido é ruído; dois são sintoma.

O que a resposta original *de fato* autorizava era relaxar o SLA de frescor,
não desligar a ingestão.

Nota adicional: a coorte também não está congelada. O registro de candidaturas
segue sujeito a indeferimento, substituição e renúncia até a eleição, então o
job `COORTE` precisa continuar rodando pelo mesmo motivo.

**Q7. ~1.000 pessoas/dia (piloto).** Média de ~0,01 req/s; mesmo uma matéria em
veículo grande multiplicando por 100 é absorvida pela CDN sem tocar no compute.

Três consequências:

1. **O Redis deixa de se justificar** (A2). Com esse volume, ele fica tão frio
   quanto a borda, e um cache frio é só custo: US$ 19–21/mês, um SPOF já
   documentado no B2 e uma dependência de runtime, para acerto próximo de zero.
   O gatilho de revisão que a tabela de decisões registrava — "medir a taxa de
   acerto na borda" — é respondido pela volumetria antes mesmo da medição.
   **✅ Decidido em 31/08/2026: Redis removido**, ficando a borda como camada
   única. Aplicado nos diagramas C4, na § 7, no BACKEND.md e nos dois planos
   de custo. O gatilho para reabrir é volumetria que a borda não absorva.
2. **O banco burstable serve tranquilamente** o tráfego de leitura. O risco de
   crédito de CPU está todo no backfill, não no atendimento — rodar o backfill
   fora do horário de pico, ou temporariamente numa instância maior.
3. **O WAF continua justificado**, e não por carga: a exposição aqui é ataque
   dirigido em período eleitoral (B7), que independe do tráfego legítimo.

### Regras de negócio e governança

**Q8. O owner é o curador.** SLA proposto, dado que a eleição é em outubro:
**a fila é zerada antes do lançamento**; depois, revisão semanal, com
priorização por relevância (quem tem mandato federal e quem disputa cargo
federal primeiro).

A verificação traz uma boa notícia para o tamanho da fila: `deputados.csv` traz
`nomeCivil` e `dataNascimento` **100% preenchidos de 2011 em diante**, e o TSE
publica os dois campos equivalentes. O casamento TSE↔Câmara pode ser
**determinístico** por (nome civil normalizado + data de nascimento), deixando
o fuzzy — e a curadoria — para a cauda, não para o caso geral.

✅ **Aplicado em 31/08/2026:** `revisado_por` e `revisado_em` em
`identificador_externo`, com `CHECK` que impede marcar como revisado sem dizer
quem e quando (T32/T33). Com um curador único a auditoria importa mais, não
menos: é o registro de que uma decisão discricionária foi tomada, por quem e
quando.

**Q9. Histórico de correção retroativa: registrado, não publicado.**
Tabela `voto_nominal_historico` append-only (valor anterior, valor novo,
execução que alterou, timestamp), escrita pelo worker quando um upsert muda
`voto` ou `voto_origem`. **Fora do contrato da API pública** — é ferramenta de
diagnóstico do owner, consultada por SQL.

Vale estender o mesmo tratamento a `proposicao.ementa`, o que **fecha o A5** de
quebra: o upsert não atualizava a ementa, então uma correção da Câmara nunca
chegaria à plataforma. Com histórico, dá para atualizar sem perder o que estava
lá antes.

✅ **Aplicado em 31/08/2026:** `voto_nominal_historico` e `proposicao_historico`,
escritos por *trigger* — não pelo worker — para que a correção feita por
`UPDATE` manual da curadoria também fique registrada (T34/T35/T38). Fora do
contrato público, como decidido. Falta a metade que é código: o upsert passar a
atualizar a ementa.

**Q10. Licenciado é diferenciado — e a verificação achou um blocker (B8).**

Ao procurar a fonte para `LICENCIADO`, apareceu um problema maior:
**`votacoesVotos` só lista quem registrou voto.** Em 2026 há exatamente cinco
rótulos — `Sim`, `Não`, `Abstenção`, `Artigo 17`, `Obstrução` — e **nenhum
"ausente"**. A mediana é de 398 linhas por votação para 513 cadeiras: os ~115
restantes simplesmente não aparecem no arquivo.

Ou seja: `AUSENTE` está no `tipo_voto_enum`, no contrato da API e no
`VotoBadge`, mas **nenhuma linha de nenhuma fonte produz esse valor** — é o
mesmo defeito do campo `tema` apontado no B3, especificado ponta a ponta e sem
origem. Hoje a plataforma mostraria só quem votou, omitindo a ausência em
silêncio. Registrado como **B8**.

A boa notícia é que a mesma fonte resolve os dois: `/deputados/{id}/historico`
devolve a linha do tempo de situação (`Exercício`, `Licença`, `SUPLENCIA`,
`CONVOCADO`, `FIM_MANDATO`) com data. São 7.889 chamadas uma única vez, depois
só o incremental dos ~600 em atividade — nada parecido com o N+1 do B3.

✅ **Aplicado em 31/08/2026** — foi a maior das três mudanças de schema:

1. Tabela `mandato_exercicio` (politico, casa, início, fim, situação, condição),
   carregada do histórico de status.
2. `AUSENTE` e `LICENCIADO` passam a ser **derivados**: para cada votação
   nominal, a lista de quem estava em exercício naquela data menos quem
   registrou voto. Quem estava em `Licença` vira `LICENCIADO`; o resto,
   `AUSENTE`. Quem não estava em exercício **não gera linha alguma** — não é
   ausência, é não ser deputado naquele dia.
3. `voto_origem TEXT NOT NULL` (B5) precisa ceder: linha derivada não tem
   string de origem. Adicionar `origem_registro` (`FONTE` | `DERIVADO`) com
   `CHECK` que só permite `DERIVADO` para `AUSENTE`/`LICENCIADO` e exige
   `voto_origem` quando `FONTE`. O invariante do B5 continua valendo onde
   importa — nenhum voto *declarado* perde o rótulo original.
4. A UI precisa dizer que essas duas categorias são cálculo nosso, não registro
   da Casa. É a mesma nota metodológica do B5.

Observação sobre o vocabulário da fonte: `situacao` vem com grafias
inconsistentes (`Exercício` e `Licença` capitalizados, `SUPLENCIA` e
`FIM_MANDATO` em caixa alta, além de nulos). Tratar como `mapeamento_voto` —
tabela versionada, não `switch` em código.

### Legal e operacional

**Q11. CPF descartado; fica só o HMAC.**
✅ *Já aplicado* no `cpf_hmac` com pepper (B1). A verificação **elimina a
principal dúvida sobre a utilidade do campo**: `deputados.csv` tem coluna `cpf`,
mas ela está **vazia nas 7.889 linhas**. O CPF não serve, e nunca serviria, para
casar TSE↔Câmara.

Isso reduz o papel do `cpf_hmac` a **um só**: ligar candidaturas da mesma pessoa
entre eleições diferentes dentro do TSE, onde ele é a única chave estável de
pessoa. Consequências:

- O casamento TSE↔Câmara usa (nome civil + data de nascimento), como na Q8.
- Terminada a montagem da trajetória eleitoral, o `cpf_hmac` **pode ser zerado**
  — o vínculo já vive em `identificador_externo`. Adicionar um passo de
  expurgo ao fim do job `COORTE` transforma a coluna em transitória, o que é
  a posição de minimização mais defensável possível.
- Sem parecer de DPO: o tratamento se limita a dado público de quem se
  apresenta ao eleitorado, o CPF nunca é exibido nem devolvido pela API, e
  agora tem prazo de vida. Registrado como decisão consciente do owner, não
  como parecer jurídico.

**Q12. Operação pelo próprio owner, sob demanda, sem plantão.**
Isso define o desenho de alertas: **poucos, acionáveis e nenhum de madrugada**.

- Ingestão falhou **dois dias seguidos** (um dia se recupera sozinho).
- Quarentena com motivo alertável acima de zero (ver Q3 — depende de separar
  `FORA_DA_COORTE`).
- 5xx sustentado na borda.

Nada mais dispara notificação. A recuperação automática é o próprio job do dia
seguinte, que é idempotente por construção. **Reforça o A14:** não há operador
para pagar o custo cognitivo de Kubernetes, e ECS/Cloud Run com a recomendação
R6 continua a resposta certa.

**Q13. Orçamento não é teto rígido, mas gasto precisa de aprovação prévia.**
Traduzido em regra operacional: nenhum recurso novo é provisionado sem estar
listado e aprovado antes; alarme de billing em 50%, 80% e 100% do previsto; e
**nada que escale com tráfego fica sem limite superior** — teto de tasks,
limite de requisições no WAF e na CDN.

A conta fecha bem: remover o Redis (Q7) libera ~US$ 20/mês, que cobre com folga
o WAF e a retenção de backup da Q5 — as duas mitigações que o A3 e o B7 pediam
e que o orçamento original não comportava.

---

## 📋 O que as respostas geraram — aplicado em 31/08/2026

As três mudanças de schema decorrentes das respostas estão em
[`db/schema.sql`](../db/schema.sql), cada uma com invariante executável em
[`db/test_invariantes.sql`](../db/test_invariantes.sql):

| # | O que | Origem | Invariantes |
|---|---|---|---|
| P1 | `mandato_exercicio`, `mapeamento_situacao`, `LICENCIADO`, `origem_registro` | Q10 / B8 | T25–T31, T39 |
| P2 | `FORA_DA_COORTE` + dedup da quarentena | Q3 | T36, T37 |
| P3 | `revisado_por`/`revisado_em`, `voto_nominal_historico`, `proposicao_historico` | Q8, Q9 / A5, A7 | T32–T35, T38 |

Três decisões de desenho que apareceram na implementação e valem registro:

1. **A ausência é calculada, e o banco protege o cálculo.** `mandato_exercicio`
   tem `EXCLUDE USING gist` proibindo períodos sobrepostos por (político, casa).
   Sem isso, a mesma pessoa poderia constar em `EXERCICIO` e em `LICENCA` no
   mesmo dia, e a derivação marcaria como falta o que era licença — o erro
   exato que a pergunta 10 pedia para evitar.
2. **O `CHECK` do voto derivado não proíbe `AUSENTE` vindo da fonte.** Se o
   Senado publicar ausência com rótulo próprio, ela entra como fato, com o
   rótulo preservado (T28). O que fica proibido é linha derivada fingindo ter
   origem, e voto declarado sem rótulo — a garantia do B5, intacta.
3. **O histórico é gravado por trigger, não pelo worker.** A correção
   retroativa precisa ser registrada também quando vem de `UPDATE` manual da
   curadoria, que é justamente o caso que ninguém lembraria de instrumentar. O
   worker anuncia sua execução com `SET LOCAL votecomdados.execucao_id`; sem
   isso a alteração aparece como manual, que é a leitura correta (T34/T35).

### Achado colateral: o schema e as migrations já tinham divergido

Ao aplicar P1–P3 apareceu um defeito que não veio das respostas.
`db/schema.sql` e `backend/.../db/migration/V1__init.sql` são duas
representações do mesmo banco, e **estavam fora de sincronia**: o schema havia
ganhado `proposicao_tema`, `cobertura_fonte` e outras tabelas que a migration
não tinha.

O que torna o caso instrutivo é que **o build continuava verde**. Os testes de
integração sobem o banco pelas migrations, então enxergam apenas esse caminho;
a divergência só apareceria em produção, como consulta contra coluna
inexistente. É a mesma classe de falha silenciosa que o B4 e o B8 tratam, só
que na infraestrutura em vez de no dado.

Corrigido com [`db/validar-migrations.sh`](../db/validar-migrations.sh), que
aplica os dois caminhos em bancos separados e compara `pg_dump --schema-only`
mais o conteúdo das tabelas de referência. Hoje o diff é vazio — inclusive a
ordem das colunas, o que exigiu declarar as colunas novas na mesma posição em
que um `ALTER TABLE` as coloca.

**O que continua pendente, e é trabalho de worker, não de schema:** o upsert de
`proposicao` passar a atualizar `ementa` (a outra metade do A5), o passo de
expurgo do `cpf_hmac` ao fim do `COORTE` (Q11) e a carga de
`mandato_exercicio` a partir de `/deputados/{id}/historico`.

---

## 🏁 Encerramento da revisão — 31/08/2026

A revisão está **encerrada**. O que ela existia para fazer — achar defeito antes
de virar código — foi feito, e o que sobrou está nomeado abaixo em vez de
diluído.

| Categoria | Total | Situação |
|---|---:|---|
| Blockers (B1–B8) | 8 | Todos corrigidos, cada um com invariante executável |
| Avisos (A1–A14) | 14 | 12 resolvidos · 2 aceitos com gatilho (A6, A9) · nenhum aberto |
| Perguntas (Q1–Q13) | 13 | Todas respondidas; duas premissas corrigidas contra o dado real |
| Recomendações (R1–R8) | 8 | Todas aceitas e aplicadas |
| Mudanças de schema (P1–P3) | 3 | Aplicadas, com migrations V2–V5 |

Cinquenta invariantes executáveis sustentam isso (`./db/validar.sh`), mais três
verificações de coerência que nasceram de defeitos reais encontrados no
caminho: schema × migrations, contrato de enums entre banco/Java/TypeScript, e
mapeamento de voto contra amostra real da fonte.

### O que fica aberto, e é honesto dizer

Os três itens abaixo têm plano em
[PLANO_IMPLEMENTACAO.md](PLANO_IMPLEMENTACAO.md), escrito em 31/08/2026.

**1. ~~A12 — o Senado~~ — fechado em 31/08/2026.** O spike foi executado e
respondeu as cinco perguntas. Ver
[PLANO_IMPLEMENTACAO.md § 1](PLANO_IMPLEMENTACAO.md#1-a12--o-senado).

Ele deixou em seu lugar **uma decisão de produto** (o Senado entra no MVP ou é
marcado como fora do escopo) e **um defeito estrutural novo**: `cobertura_fonte`
é chaveada por `(esfera, uf, recurso)` e não consegue representar duas Casas na
mesma esfera federal — hoje ela afirma sobre senadores algo que não é verdade,
e continuaria afirmando mesmo se o Senado ficasse de fora.

**2. O worker não existe.** Schema, contrato e frontend estão prontos; a
ingestão não foi escrita. O que já está especificado e ainda precisa virar
código: carga de `mandato_exercicio` a partir de `/deputados/{id}/historico`,
upsert de `proposicao` atualizando `ementa`, expurgo do `cpf_hmac` ao fim do
`COORTE`, chamada de `reconstruir_perfil_leitura()` e publicação do pacote de
dados abertos.

**3. Nenhuma tela aponta para os dados abertos.** Falta um rodapé no frontend.
Dado publicado que ninguém encontra não foi publicado de verdade.

### O que a revisão ensinou sobre revisões

Três dos achados mais sérios **não vieram da leitura dos documentos**:

- o **B8** (`AUSENTE` sem fonte) apareceu ao baixar o CSV da Câmara e contar
  rótulos;
- o `unaccent()` não-imutável apareceu ao rodar o DDL pela primeira vez;
- a divergência entre `schema.sql` e `V1__init.sql` apareceu ao comparar os
  dois caminhos de criação do banco — e tinha passado por todos os testes.

O padrão é o mesmo: **revisão de documento pega contradição interna; só
execução pega contradição com a realidade.** Foi por isso que cada correção
virou script executável em vez de parágrafo, e é o critério que deve valer para
a próxima revisão.
