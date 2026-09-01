# VoteComDados — Arquitetura de Backend

Java 21 (LTS) + Spring Boot 4, com SQL escrito à mão (sem ORM) e concorrência
**blocking + virtual threads**. Dois artefatos deployáveis separados — API e
ingestão — mapeados 1:1 nas duas linhas de ECS Fargate já orçadas em
[CUSTOS_INFRA_AWS.md](CUSTOS_INFRA_AWS.md).

> ### Duas divergências deste documento, decididas na implementação
>
> **`JdbcClient` em vez de jOOQ.** A intenção original — SQL real, sem
> abstração de ORM — está preservada; o que mudou foi a ferramenta. O codegen
> do jOOQ exige um banco disponível *durante o build*, e o ambiente de
> desenvolvimento não tem JDK local (tudo compila em container), o que tornaria
> cada build uma orquestração de rede entre containers. O custo da troca é
> perder a verificação do SQL em tempo de compilação, compensada pelos testes
> de integração, que executam cada consulta contra o schema real. Revisitar
> quando houver JDK local ou CI com Docker-in-Docker.
>
> **Spring Boot 4, não 3.** O 3.5.x está próximo do fim de suporte. A migração
> trouxe duas mudanças que afetam o que está descrito adiante: as
> auto-configurações foram divididas em módulos (ter `flyway-core` no classpath
> **não** executa migrations — é preciso `spring-boot-starter-flyway`, e a
> falha é silenciosa), e `TestRestTemplate` foi removido em favor de
> `RestClient`.
>
> O estado implementado e como rodar estão em
> [`backend/README.md`](../backend/README.md).

> A separação em dois artefatos (service 24/7 + job sob demanda) e a
> camada de dados (SQL, migrations, Postgres) descritas aqui não
> dependem do provedor de nuvem. Os detalhes de empacotamento/deploy da
> seção 7, porém, usam nomenclatura AWS (ECS, ALB, ECR); o provedor ainda
> não foi decidido — ver [CUSTOS_INFRA_GCP.md](CUSTOS_INFRA_GCP.md) para a
> tradução desses mesmos dois artefatos para Cloud Run / Cloud Run Jobs.

## 1. Por que essas escolhas

- **SQL à mão em vez de JPA/Hibernate:** o schema (`db/schema.sql`) depende de
  `ON CONFLICT` para upsert idempotente, coluna gerada `tsvector` e busca
  por `pg_trgm`. Mapear isso em JPA exigiria `@Query` nativa na maior parte
  dos repositórios de qualquer forma, e o projeto não precisa da ilusão de
  portabilidade (o schema já é Postgres-específico por design). A escolha
  concreta é `JdbcClient` — ver a nota de divergência acima.
- **Virtual threads em vez de WebFlux:** o volume de tráfego previsto
  (`CUSTOS_INFRA_AWS.md`, cenário "pequeno") não justifica o custo de
  legibilidade/depuração do modelo reativo. Virtual threads (Java 21, GA)
  dão a maior parte do ganho de I/O do WebFlux com código síncrono comum —
  inclusive nos clients HTTP para Câmara/Senado/TSE.
- **Dois artefatos, não um monólito único:** o worker de ingestão roda sob
  demanda (job que termina e sai, faturado só pelo tempo de execução — ver
  `CUSTOS_INFRA_AWS.md`), enquanto a API roda 24/7 atrás do ALB. Empacotar os
  dois juntos forçaria a API a pagar pelo footprint de memória do worker
  (ou vice-versa) sem necessidade.

## 2. Estrutura do projeto (multi-módulo Maven)

```
votecomdados/
  pom.xml                        # parent: gerencia versões, plugins comuns
  votecomdados-core/              # sem Spring Boot — modelo compartilhado
    src/main/java/.../core/
      dominio/                    # enums e records espelhando o schema e o contrato da API
    src/main/resources/db/migration/
      V1__init.sql                 # schema inicial, espelhando db/schema.sql
      V2__...sql, V3__...sql        # evolução incremental — db/schema.sql é sempre o acumulado
  votecomdados-api/                # Spring Boot — serviço HTTP (ECS Fargate "API")
    src/main/java/.../api/
      web/                         # @RestController por recurso (politicos, proposicoes, votacoes, meta)
      web/dto/                     # request/response DTOs = contrato de docs/API.md
      web/error/                   # @ControllerAdvice -> envelope {error:{code,message}}
      service/                     # casos de uso: BuscarPoliticos, ObterPerfil, ListarVotacoesDoPolitico...
      repositorio/                 # SQL: PoliticoRepositorio, ProposicaoRepositorio, VotacaoRepositorio, MetaRepositorio
      config/                      # CORS; e futuramente OpenAPI
  votecomdados-ingestion/          # Spring Boot (modo batch) — worker (ECS Fargate "Worker de ingestão")
    src/main/java/.../ingestion/
      Worker.java                  # @SpringBootApplication sem servidor: sobe, trabalha, morre
      SeletorDeJob.java            # ApplicationRunner: lê --job/--fonte/--ano e delega
      execucao/                    # ControleDeExecucaoService: lock, reaper, watermark
      staging/                     # RedatorDeCamposSensiveis (allowlist), payload bruto,
                                   # quarentena e mascara de log
      coorte/                      # JobDeCoorte: TSE -> politico + candidatura,
                                   # poda e expurgo do cpf_hmac
      identidade/                  # ServicoDeResolucaoDeIdentidade: cadastro da Casa
                                   # -> pessoa da coorte; quatro desfechos
      mandato/                     # ConstrutorDePeriodos: eventos da Casa -> periodos
                                   # de exercicio; base da derivacao de ausencia
      massa/                       # CarregadorDeArquivosEmMassa: COPY para tabela
                                   # temporaria + transformacao em SQL (ELT do R1);
                                   # JobDeBackfillCamara (carga de um ano) + JobDeBackfill
                                   # (laco por ano; deriva/finaliza uma vez, ao fim)
      derivacao/                   # DerivadorDeAusencia: universo do dia menos quem
                                   # votou -> AUSENTE/LICENCIADO (fecha o B8)
      publicacao/                  # FinalizadorDeIngestao (marca possui_atuacao_legislativa,
                                   # DEPOIS reconstroi a projecao), ProjecaoDeLeitura e
                                   # ExportadorDeDadosAbertos: o que a ingestao publica ao
                                   # terminar
      download/                    # BaixadorDeArquivos (If-Modified-Since) e
                                   # JobIncremental: o ciclo diario
      senado/                      # JobDoSenado (a Casa publica a bancada inteira, entao
                                   # nada ali e derivado) + ClienteDoSenado + EnderecosDoSenado
                                   # + OrquestradorDoSenado: watermark pela maior dataSessao
                                   # vista, ja que a fonte nao publica Last-Modified
      alesp/                       # JobDaAlesp + OrquestradorDaAlesp: so voto de
                                   # COMISSAO, mapeado pelo CODIGO <TipoVoto> (8
                                   # valores) e nao pelos 477 textos livres;
                                   # LeitorDeXmlAlesp le 350 MB de XML em fluxo
```

`votecomdados-core` concentra o modelo de domínio e as migrations — API e
ingestão dependem dele e nunca duplicam a definição do schema.

## 3. Módulo `api`

### Camadas

`web` (controller) → `servico` (caso de uso) → `repositorio` (SQL) →
Postgres. Controllers são finos: validam entrada (Bean Validation),
chamam o service, mapeiam o resultado para o DTO de resposta definido em
[API.md](API.md). Nenhuma regra de negócio no controller.

### Contrato

Cada endpoint de `API.md` vira um `@RestController` + DTO com o mesmo
formato de paginação (`{data, pagination}`) e o mesmo envelope de erro
(`{error:{code, message}}`) via `@ControllerAdvice` global — nenhum
endpoint deve inventar seu próprio formato de erro.

### Cache: nenhum dentro da aplicação

**A API não tem camada de cache.** Todo cache vive na borda, via
`Cache-Control` (ver [API.md](API.md#cache-e-proteção-contra-abuso)), e a
aplicação responde cada miss indo ao Postgres.

Isso não é omissão: o desenho original tinha `@Cacheable` sobre Redis, e ele
foi removido depois de a volumetria ser definida (~1.000 visitas/dia). Nessa
escala o cache in-memory ficaria tão frio quanto a borda, e trazia junto um
`CacheErrorHandler` de fail-open — necessário porque o Spring Cache **propaga
exceção por padrão**, transformando um cache indisponível em erro 500. Era
configuração obrigatória para sobreviver à queda de algo que não entregava
nada. Ver [ARQUITETURA.md § 7](ARQUITETURA.md#por-que-não-há-cache-in-memory-atrás-da-api).

Consequência prática para quem for implementar: **nenhum service deve levar
`@Cacheable`**, e o alvo de p95 da API é para o caminho que vai ao banco. Se um
endpoint não couber nesse alvo, a resposta é índice ou consulta melhor — não
cache.

### Health checks e ciclo de vida do container

- **Grupos separados**, com o load balancer apontando para *readiness*:
  ```yaml
  management.endpoint.health.group.readiness.include: db      # só o banco
  management.endpoint.health.group.liveness.include: ping     # só o processo
  ```
  Usar `/actuator/health` (agregado) no health check causa falha em cascata:
  qualquer dependência lateral cai → todos os containers `DOWN` → o
  balanceador esvazia a frota. A regra vale mesmo sem Redis: toda dependência
  nova fica fora dos dois grupos até que se decida o contrário. Ver
  [ARQUITETURA.md § 9](ARQUITETURA.md#health-checks-o-que-pode-e-o-que-não-pode-derrubar-a-frota).
- **Graceful shutdown:** `server.shutdown=graceful` +
  `spring.lifecycle.timeout-per-shutdown-phase=25s`, com o `stopTimeout` da
  task **maior** que esse valor e o *deregistration delay* do balanceador
  alinhado — sem isso, todo deploy corta conexões em voo.
- **Memória:** `-XX:MaxRAMPercentage=75`. A JVM ignora limites de container
  se não for configurada e é morta por OOM antes de rodar GC.

### Observabilidade e operação

- **Actuator** (`/actuator/health/readiness`, `/actuator/info`) alimenta o
  health check do target group do ALB.
- **springdoc-openapi** gera o OpenAPI a partir dos controllers — usado
  pelo frontend para manter `types/api.ts` (FRONTEND.md) alinhado ao
  contrato real, não só ao markdown.
- **Micrometer + `micrometer-registry-cloudwatch2`** publica métricas
  (latência por rota, taxa de erro, saturação do pool de conexões) direto no CloudWatch
  já orçado.
- Logs estruturados em JSON (Logback + `logstash-logback-encoder`), com
  `request-id` por requisição para correlacionar com o ALB access log, e
  **mascaramento de campos sensíveis no appender** — o worker loga payload em
  caso de erro, e é o caminho mais fácil de vazar CPF sem perceber.
- **Métricas de negócio**, não só de sistema: cobertura de vínculos
  resolvidos, vínculos pendentes de curadoria, registros em quarentena por
  fonte e motivo, defasagem por fonte. Uptime verde com 30% dos votos em
  quarentena é um sistema falhando em silêncio — só a métrica de negócio
  revela.

## 4. Módulo `ingestion`

Não é um serviço long-running: é um **job batch** — `ApplicationRunner`
que executa, faz upsert idempotente e sai (`System.exit`), disparado pelo
EventBridge Scheduler via `ecs:RunTask` (conforme ARQUITETURA.md). Isso é
o que permite o worker ser cobrado só pelas horas efetivas de execução em
`CUSTOS_INFRA_AWS.md`, em vez de rodar 24/7.

```
java -jar ingestion.jar --job=backfill --fonte=camara --desde=2001 --ate=2026
java -jar ingestion.jar --job=incremental --fonte=camara
java -jar ingestion.jar --job=incremental --fonte=senado
java -jar ingestion.jar --job=incremental --fonte=alesp
```

`--desde` e `--ate` do backfill têm default (2001 — primeiro ano com voto
nominal da Câmara — e o ano corrente), e servem de retomada: um backfill de
25 anos que morre no meio não recomeça do zero, o operador só passa
`--desde=<onde parou>`. Cada ano é upsert idempotente, então pedir um
`--desde` anterior ao necessário reprocessa sem duplicar.

### Pipeline dentro do job

0. **Abertura da execução** (`ControleDeExecucaoService`): toma
   `pg_try_advisory_lock` da fonte, roda o reaper de execuções órfãs, grava
   uma linha em `ingestao_execucao` com `status = 'EM_ANDAMENTO'` e lê o
   watermark da última execução **bem-sucedida** daquela fonte. Não obteve o
   lock, encerra com log — não enfileira. Ver
   [ARQUITETURA.md § 5](ARQUITETURA.md#exclusão-mútua-uma-execução-por-fonte).
1. **Coleta.** Dois caminhos distintos, e a escolha entre eles é a decisão
   mais consequente do pipeline:
   - **Backfill → `CarregadorDeArquivos`:** baixa o CSV anual da fonte e
     carrega com `COPY`. Usar a API REST aqui significaria três chamadas por
     proposição e uma por votação — N+1 contra API de terceiro.
   - **Incremental → `BaixadorDeArquivos` (Câmara e Alesp, por
     `If-Modified-Since`) ou `ClienteDoSenado` (`HttpClient` puro):** volume
     pequeno, request/response adequado. O Senado é o caso à parte — a API
     não publica `Last-Modified` nem `ETag`, então não há a quem perguntar
     "mudou?"; o watermark vem da maior `dataSessao` vista, não do HTTP.
2. **Resiliência:** Resilience4j `Retry` (backoff exponencial + jitter,
   respeita `Retry-After`) e `RateLimiter`/`Bulkhead` para não sobrecarregar
   APIs de governo com a concorrência que virtual threads permitiriam.
3. **Filtro de coorte** (`FiltroDeCoorte`): os arquivos anuais contêm todos os
   parlamentares — não há como pedir só os da coorte à fonte. O descarte
   acontece **na carga**: quem não é candidato em 2026 não vira `politico`.
   Coautor fora da coorte é preservado como `proposicao_autor.autor_nome`, sem
   registro pessoal, para que a autoria da matéria continue completa.
4. **Redação** (`RedatorDeCamposSensiveis`): allowlist de campos por
   `(fonte, recurso)` antes de qualquer persistência ou log. O dataset do TSE
   contém `NR_CPF_CANDIDATO` — sem esta etapa, o staging seria uma base de
   CPFs em claro.
5. **Staging** (`RawPayloadRepository`): persiste o payload **já redigido** em
   `staging.payload_bruto`, com dedup por hash, antes de normalizar. Permite
   reprocessar um bug de normalização localmente em vez de repetir a coleta.
6. **Normalização:** mapeia o payload de cada fonte para o modelo comum em
   `votecomdados-core`. A tradução do voto consulta `mapeamento_voto` (tabela,
   não `switch`) e **preserva a string original em `voto_origem`** — o enum é
   interpretação nossa, a string é o fato.
7. **Entity Resolution** (`resolution/`): tenta casar por `cpf_hmac`
   (determinístico); se ausente, cai para o fuzzy match via
   `similarity()` do `pg_trgm` sobre nome + UF + partido, gravando
   `score_confianca` e `revisado_manualmente = false` quando abaixo do
   threshold — a curadoria manual desses casos é um processo separado
   (fora do escopo do MVP; hoje é uma consulta SQL direta feita pelo
   curador).
8. **Quarentena** (`ServicoDeQuarentena`): registro que não pôde ser
   resolvido — voto de parlamentar sem vínculo, voto sem entrada em
   `mapeamento_voto` — vai para `staging.registro_rejeitado` com motivo e
   payload. **Nunca é descartado nem faz o job inteiro falhar.** Ver
   [ARQUITETURA.md § 5](ARQUITETURA.md#quarentena-falhar-visível-em-vez-de-descartar-em-silêncio).
9. **Persistência:** todo upsert usa `ON CONFLICT` (já no schema),
   garantindo que rodar o mesmo job duas vezes não duplica dados — a
   idempotência é o que torna seguro reprocessar após falha, sem precisar
   de "modo dry-run" separado.
10. **Encerramento:** marca a execução como `CONCLUIDA` e grava
   `watermark_novo` com `GREATEST(novo, atual)` — o marcador nunca retrocede.
   Em exceção não tratada, marca `FALHA` com a mensagem, e o watermark
   permanece onde estava. **O worker não chama o CI:** o rebuild do site é
   decidido pelo próprio CI, comparando o watermark com o último build (um
   webhook perdido deixaria o site sem reconstruir em silêncio).

### Ordem de ingestão

Não é detalhe de implementação: é o que mantém a quarentena vazia. Votos e
autoria referenciam `politico` por FK, então o cadastro tem de vir primeiro.

```
cadastro de parlamentares  →  proposições e votações  →  votos e autoria
```

### Tipos de job

| Job | Uso | Quando roda |
|---|---|---|
| `coorte` | Sincroniza a lista de candidatos de 2026 no TSE e **poda** quem saiu | Cron diário — pré-requisito dos demais |
| `backfill` | Todo o histórico da coorte, por arquivos em massa, fatiado por `(fonte, ano)` | Sob demanda; e para quem entra na coorte depois |
| `incremental` | Novas/alteradas votações e proposições via REST desde o watermark | Cron diário (EventBridge Scheduler) |

O `coorte` roda **antes** dos outros: sem a lista de candidatos não há como
saber o que filtrar. Quando ele admite alguém novo — registro deferido em
recurso, substituição de chapa — o `backfill` precisa rodar para aquela
pessoa, o que é barato porque os CSVs já estão arquivados no object storage e
o filtro é uma consulta SQL, sem rede.

## 5. Banco de dados e migrations

- **Flyway** assume o versionamento: `db/schema.sql` é o **estado acumulado**
  (o banco criado do zero), e as migrations são o **caminho** até ele. Toda
  mudança entra como `V2__...`, `V3__...` — nunca por edição retroativa de uma
  migration já aplicada.
- **As duas fontes precisam ser verificadas uma contra a outra**, e isso não é
  zelo: elas já divergiram em silêncio uma vez. `db/schema.sql` ganhou tabelas
  e `V1__init.sql` ficou para trás; o build continuou verde, porque os testes
  só enxergam o caminho das migrations. A divergência só apareceria em
  produção, como consulta contra coluna inexistente. Por isso
  [`db/validar-migrations.sh`](../db/validar-migrations.sh) aplica os dois
  caminhos em bancos separados e compara `pg_dump --schema-only` mais o
  conteúdo das tabelas de referência — tem de dar diff vazio.
- **Valor novo de enum vai em migration própria.** O Postgres aceita
  `ALTER TYPE ... ADD VALUE` dentro de uma transação (e o Flyway roda cada
  migration em uma), mas proíbe *usar* o valor na mesma transação. Foi por isso
  que a V2 existe separada da V3.
- **Sem passo de codegen.** Com SQL escrito à mão não há geração de código, o
  que mantém o build simples — o preço é que a correspondência entre consulta e
  schema só é verificada pelos testes de integração, e não pelo compilador.

## 6. Testes

| Tipo | Ferramenta | Cobre |
|---|---|---|
| Unitário | JUnit 5 + Mockito | Services, mapeadores, `EntityResolutionService` |
| Integração (API) | `@SpringBootTest` + Testcontainers (Postgres) | Consultas SQL e controllers ponta a ponta — **implementado** |
| Contrato | RestAssured contra o OpenAPI gerado | Resposta de cada endpoint bate com `API.md` |
| Ingestão | WireMock (stub Câmara/Senado/TSE) + Testcontainers | Paginação, retry/backoff, idempotência (rodar o job 2x = mesmo resultado) |

## 7. Empacotamento e deploy

Build multi-módulo Maven → cada módulo deployável (`api`, `ingestion`)
gera um fat jar Spring Boot → imagem Docker multi-stage
(`eclipse-temurin:21-jre-alpine` no runtime, para manter a imagem e o
footprint de memória pequenos) → push para ECR → dois recursos ECS
distintos:

- `votecomdados-api`: ECS **Service** (long-running, atrás do ALB, health
  check em `/actuator/health`) — linha "ECS Fargate — API" do plano de
  custos.
- `votecomdados-ingestion`: ECS **Task** disparada pelo EventBridge
  Scheduler (`RunTask`, sem service associado, sem ALB) — linha "ECS
  Fargate — Worker de ingestão".

Secrets (credencial de banco e pepper do HMAC) injetados via `secrets` da task
definition, lidos do Secrets Manager já orçado — nunca em variável de
ambiente em texto plano no repositório.
