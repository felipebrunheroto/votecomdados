# Plano — DevSecOps e Infraestrutura como Código

Hoje **não existe nenhuma automação de nuvem**: o diretório nem é repositório
git (`git status` falha com "not a git repository"), não há Dockerfile, não
há workflow de CI, não há uma linha de Terraform. Tudo o que existe é
especificação — em [ARQUITETURA.md](ARQUITETURA.md),
[BACKEND.md](BACKEND.md) § 7, [FRONTEND.md](FRONTEND.md) § 9 e nos dois
planos de custo — e um `docker compose` local que prova que a aplicação
funciona, mas nunca tocou uma nuvem de verdade.

Este plano não inventa arquitetura nova: ele **implementa o que os outros
documentos já decidiram**, na ordem que faz sentido para um projeto com
essas restrições específicas — um único operador sem plantão
([ARQUITETURA.md § 9](ARQUITETURA.md#operação-um-operador-sob-demanda-sem-plantão)),
orçamento de ~US$ 236 para 45 dias de produção na AWS, região `us-east-1`
([CUSTOS_INFRA_AWS.md](CUSTOS_INFRA_AWS.md)), sem ambiente de staging.
Ferramenta que não se paga sozinha nesse contexto não entra — é a mesma
régua que já cortou Redis, Kubernetes e um broker de fila do resto do
projeto.

**Decidido em 01–02/09/2026 (§ 3): repositório GitHub público, provedor AWS,
conta existente, região `us-east-1`.** O plano equivalente em
[CUSTOS_INFRA_GCP.md](CUSTOS_INFRA_GCP.md) continua mantido como referência
de comparação, mas deixa de ser implementado por este plano.

> O account ID em si não é segredo para a AWS, mas este repositório vai ser
> **público** (D1) — sem necessidade real de publicá-lo em prosa, ele fica
> só em `infra/terraform.tfvars` (gitignored, § 3, tabela de dados) e nas
> configurações do GitHub Actions, nunca em markdown versionado.

---

## 1. O que já está decidido (ponto de partida, não deste plano)

| Decisão | Onde |
|---|---|
| GitHub Actions como pipeline de build/publicação | [ARQUITETURA.md § 2](ARQUITETURA.md#2-nível-2--diagrama-de-containers), tabela de mapeamento |
| Empacotamento: Maven multi-módulo → fat jar → Docker multi-stage (`eclipse-temurin:21-jre-alpine`) → ECR → ECS Service (API) + ECS Task (worker) | [BACKEND.md § 7](BACKEND.md#7-empacotamento-e-deploy) |
| Segredos só no gerenciador do provedor, injetados na execução, nunca versionados | [ARQUITETURA.md § 10](ARQUITETURA.md#10-segurança-e-dados-pessoais) |
| API com credencial `SELECT`-only; escrita é exclusividade do worker | [ARQUITETURA.md § 10](ARQUITETURA.md#10-segurança-e-dados-pessoais) |
| WAF/Cloud Armor com rate-based rule — não opcional | [ARQUITETURA.md § 10](ARQUITETURA.md#10-segurança-e-dados-pessoais), [CUSTOS_INFRA_AWS.md](CUSTOS_INFRA_AWS.md) |
| Rebuild do frontend por **pull** (cron horário comparando watermark), nunca por webhook do worker | [FRONTEND.md § 1](FRONTEND.md#pipeline-de-rebuild) — corrigido nesta sessão para bater com o ADR em [ARQUITETURA.md § 11](ARQUITETURA.md#11-decisões-de-arquitetura) |
| `--exclude 'dados-abertos/20*'` no sync do frontend, para não apagar os instantâneos | [FRONTEND.md § 9](FRONTEND.md#9-deploy-ci) |
| Fallback de perfil sem pré-render: Custom Error Response no CloudFront / rewrite no Firebase Hosting | [PLANO_CORRECAO_STATIC_PARAMS.md](PLANO_CORRECAO_STATIC_PARAMS.md), notas em ambos `CUSTOS_INFRA_*.md` |
| Sem Kubernetes, sem staging, sem NAT Gateway, single-AZ com PITR | [ARQUITETURA.md § 9 e § 11](ARQUITETURA.md#9-requisitos-não-funcionais) |
| Alarmes de billing (50/80/100%), 5xx sustentado, ingestão falhou 2 dias seguidos | [ARQUITETURA.md § 9](ARQUITETURA.md#operação-um-operador-sob-demanda-sem-plantão) |
| Graceful shutdown, probes separadas, `-XX:MaxRAMPercentage=75` | [BACKEND.md § 3](BACKEND.md#health-checks-e-ciclo-de-vida-do-container), R6 em [REVISAO_ARQUITETURA.md](REVISAO_ARQUITETURA.md) |

Este plano existe para transformar essa especificação em código executável
— Dockerfiles, módulos Terraform, workflows — e em torno dela, adicionar o
que ainda não tinha sido pensado: como o pipeline se protege de vulnerabilidade
em dependência, segredo vazado, imagem com CVE e infraestrutura mal
configurada, dado que hoje nada disso é verificado automaticamente.

## 2. Princípios que guiam as escolhas de ferramenta

- **Uma ferramenta cobrindo três propósitos vale mais que três ferramentas
  cobrindo um cada**, no mesmo espírito que já cortou Redis e o broker de
  fila do resto do projeto. Onde dá, prefira o que o operador único
  consegue manter sozinho.
- **Grátis primeiro, nesta escala.** O orçamento de infraestrutura já é
  apertado (nota em CUSTOS_INFRA_AWS.md sobre a margem sumir ao pular o
  piloto); não sobra orçamento de segurança separado. Toda ferramenta abaixo
  é gratuita nas condições descritas — a única que **não** é (GitHub
  Advanced Security num repositório privado) está marcada como decisão a
  tomar, não como escolha já feita.
- **IaC para o provedor decidido, sem abstração multi-cloud.** Com AWS
  fechado (§ 3), não há motivo para um módulo genérico "multi-cloud"
  tentando esconder que ECS e RDS são específicos da AWS — é a mesma lição
  de "três linhas parecidas é melhor que abstração prematura" que já rege o
  resto do código. `infra/` é uma árvore única.
- **Nada que exija plantão para operar.** Toda automação deve falhar de
  forma visível e recuperável no próximo ciclo (mesmo raciocínio do
  watermark de ingestão e do `build-info.json` do frontend) — nunca
  silenciosamente, e nunca dependendo de alguém acordar de madrugada.

## 3. Decisões do owner

| # | Decisão | Resposta | Por quê |
|---|---|---|---|
| D1 | Repositório GitHub público ou privado? | **✅ Público** (decidido 01/09/2026) | CodeQL (SAST), secret scanning e push protection nativos do GitHub são grátis só em repositório público (ou com GitHub Advanced Security pago, fora do orçamento). É também coerente com a missão de dados abertos. Segredos de runtime nunca entram no repositório de qualquer forma (item já decidido, § 1), então tornar o repo público não expõe credencial nenhuma. |
| D2 | AWS ou GCP como provedor final? | **✅ AWS** (decidido 01/09/2026) | Fecha a árvore Terraform única em `infra/`, sem duplicar módulos para um provedor que não vai rodar. [CUSTOS_INFRA_GCP.md](CUSTOS_INFRA_GCP.md) continua como referência de comparação, não como implementação paralela. |
| D3 | Conta AWS: já existe, ou precisa ser criada? | **✅ Já existe** (decidido 02/09/2026) | Bloqueia a Fase 4 (bootstrap do estado remoto) e a Fase 6 (federação OIDC) — precisa do account ID e de outros dados (ver caixa abaixo) antes de escrever isso em código. |
| D3b | Quem roda os comandos de bootstrap da Fase 4 — o owner (com o acesso que já tem) ou uma credencial temporária configurada nesta sessão? | **✅ O owner roda** (decidido 02/09/2026) | Bootstrap cria recursos sensíveis (bucket de state, tabela de lock, provider OIDC do IAM) uma única vez. Com o owner rodando, esta sessão nunca precisa segurar credencial nenhuma, nem temporária — depois do bootstrap, tudo mais passa a autenticar via OIDC (sem chave estática). |
| D4 | `terraform apply` em produção: aprovação automática no merge, ou um clique manual de confirmação? | **✅ Clique manual** (decidido 02/09/2026), via GitHub Environments com o próprio owner como *required reviewer* do ambiente `production` | Dinheiro real, operador único, sem colega para revisar o PR antes. Um ambiente do GitHub com aprovação obrigatória funciona como o "tem certeza?" que substitui a segunda pessoa — e fica registrado em log. `plan` continua automático em todo PR. |

Todas as quatro decisões estão fechadas, e os cinco dados que a Fase 4
precisa (account ID, região, domínio, conta/nome do GitHub, e-mail de
billing) já foram confirmados — ver a tabela na Fase 4 abaixo. Falta só
escrever e rodar o runbook.

## 4. Ferramentas escolhidas

| Preocupação | Ferramenta | Por que essa, e não outra |
|---|---|---|
| IaC | **Terraform** | Considerado o AWS CDK (nativo, código imperativo em Java/TypeScript) e preterido: HCL declarativo é mais simples de revisar num `plan` de PR do que o resultado de rodar código imperativo — importa mais aqui, com um único revisor (D4), do que a conveniência de escrever infra na mesma linguagem da aplicação. O ecossistema de scanning (Trivy `config`) também é mais maduro para Terraform do que para CDK. |
| CI/CD | **GitHub Actions** | Já decidido em ARQUITETURA.md. Grátis para repositório público (item D1), integra nativamente com OIDC para os dois provedores, e é onde CodeQL/Dependabot/secret scanning já vivem — um só lugar para configurar. |
| SCA (dependência vulnerável) + atualização | **Dependabot** | Nativo do GitHub, grátis em repositório público **ou** privado, cobre Maven, npm, Docker base image e as próprias GitHub Actions usadas nos workflows. Abre PR sozinho — não exige triagem manual de relatório. |
| SAST (código) | **CodeQL** (default setup) | Nativo, grátis em repositório público, cobre Java e TypeScript com um único workflow. Não adiciona conta nem chave de API de terceiro. |
| Secret scanning | **GitHub secret scanning + push protection** (nativo) | Bloqueia o *push* antes do segredo entrar no histórico, não só depois — mais forte que rodar um scanner em CI, que só avisa depois que o dano (segredo já no histórico) aconteceu. Grátis em repositório público. |
| Imagem de container (CVE), IaC (config errada) e SBOM | **Trivy**, um único binário para os três | Evita instalar tfsec/Checkov/Grype/Syft separados para tarefas adjacentes — Trivy cobre `image`, `config` (Terraform) e `sbom` (CycloneDX) no mesmo comando, com um único cache de banco de vulnerabilidades para manter atualizado. |
| DAST | **Nenhuma automatizada** (ver § 8) | Sem staging, atacar a própria produção em todo deploy é risco desnecessário contra o próprio orçamento de CPU do banco que o WAF existe para proteger. Um scan passivo único, manual, antes do go-live — não em pipeline. |

## 5. Fases

### Fase 0 — Fundações do repositório ✅ Entregue (02/09/2026)

Pré-requisito literal de tudo mais: sem `git`, não existe GitHub Actions.

1. ~~`git init`, `.gitignore`~~ — feito (`target/`, `node_modules/`,
   `web/.next/`, `web/out/`, `infra/.terraform/`, `*.tfstate*`, `*.tfvars`,
   `.env*`, `.m2-cache/`, mais os dois arquivos locais do Claude Code).
2. ~~Primeiro commit~~ — `fd80a94`, 202 arquivos, varrido contra padrões de
   segredo (chave AKIA, `aws_secret_access_key`, chave privada PEM) antes de
   commitar.
3. ~~Criar o repositório remoto no GitHub e `git push`~~ —
   [github.com/felipebrunheroto/votecomdados](https://github.com/felipebrunheroto/votecomdados),
   público (D1), branch `main`.
4. ~~Branch protection em `main`~~ — PR obrigatório para merge (0 aprovações
   exigidas, não existe segundo revisor), sem force-push, sem exclusão do
   branch. `required_status_checks` fica `null` por enquanto — não há
   workflow ainda para exigir; entra assim que a Fase 1 existir.

**Como se prova:** `git log` mostra o histórico ✓; o repositório aparece
público no GitHub ✓; branch protection ativa, confirmada via
`gh api repos/felipebrunheroto/votecomdados/branches/main/protection` ✓.
Falta só confirmar o bloqueio de push direto com um PR de teste, depois que
a Fase 1 existir para dar um check real a exigir.

### Fase 1 — CI de qualidade ✅ Entregue (02/09/2026)

Tudo que hoje só roda manualmente (esta sessão inteira dependeu disso)
passa a rodar em todo PR e push para `main`. Zero infraestrutura de nuvem
envolvida — começou antes de D2/D3 estarem resolvidas, como previsto.

| Workflow | O que roda | Origem |
|---|---|---|
| `.github/workflows/backend.yml` (check `test`) | `mvn clean test` (162 testes, Testcontainers) | já existia como `./mvnd clean test`, só nunca tinha rodado em CI — no runner, `actions/setup-java` substitui o wrapper Docker local, que só existe por falta de JDK na máquina de dev |
| `.github/workflows/db-guards.yml` (check `guards`) | `db/validar-migrations.sh`, `db/validar.sh`, `node tools/validar-contrato.mjs` | já existiam, mesma situação |
| `.github/workflows/frontend.yml` (check `build`) | `npm run typecheck`, `npm run lint`, `npm run build` (sem `NEXT_PUBLIC_API_URL`, valida que o export com fixtures não quebra) | já existiam |
| `.github/workflows/postman.yml` (check `newman`) | `docker compose up -d db api`, aguardar `/api/v1/politicos/...` responder, `npm run rodar` | reaproveita o `compose.yml` da raiz, validado nesta sessão |

Validados com `actionlint` antes do primeiro push (zero achados). Branch
protection em `main` atualizada com os quatro checks como obrigatórios
(`strict: true` — a branch precisa estar atualizada com `main` antes de
poder mergear, não só ter passado em algum commit anterior).

**Como se prova:** PR #2 rodou os quatro workflows pela primeira vez contra
código real — `test` (1m36s), `guards` (19s), `build` (35s), `newman`
(1m17s), todos ✅ na primeira tentativa. Falta só confirmar que um PR que
quebra algum deles de propósito é bloqueado — verificação natural da
próxima vez que algo realmente quebrar, não simulada aqui.

### Fase 2 — Segurança de código e supply chain ✅ Entregue (02/09/2026)

1. `dependabot.yml`: ecossistemas `maven` (backend), `npm` (três diretórios
   — `web`, `tools`, `backend/postman`, cada um com seu próprio
   `package-lock.json`) e `github-actions`, semanal, com minor/patch
   agrupados num PR só por ecossistema (major fica de fora do agrupamento —
   merece revisão isolada). `docker` fica para a Fase 3, quando existir
   Dockerfile para escanear.
2. `.github/workflows/codeql.yml`: `java-kotlin` (build **manual**, não
   autobuild — mesma preferência por explícito do resto do backend, ver
   ADR de `JdbcClient` em [ARQUITETURA.md § 11](ARQUITETURA.md#11-decisões-de-arquitetura))
   + `javascript-typescript`, em todo PR e semanalmente (segunda, 06:00 UTC).
3. Secret scanning, push protection, Dependabot alerts e Dependabot
   security updates — todos habilitados via API do GitHub (D1 = público
   tornou isso gratuito).
4. `.github/workflows/trivy-fs.yml`: `trivy fs` nas quatro árvores de
   dependência (`backend`, `web`, `tools`, `backend/postman`), matriz de 4
   jobs. Bloqueia só `CRITICAL` **com correção disponível**
   (`ignore-unfixed: true`) — travar o merge por uma CVE sem patch não
   protege nada, só empata o repositório numa vulnerabilidade que ninguém
   consegue corrigir ainda.

Ao escrever os workflows, toda versão de action fixada (`actions/checkout`,
`actions/setup-java`, `actions/setup-node`, `github/codeql-action`,
`aquasecurity/trivy-action`) foi conferida contra a API de releases do
GitHub antes de commitar — os primeiros palpites (`@v4`/`@v3`/`@v0.28.0`)
estavam desatualizados; `actionlint` sozinho não pega isso, porque a tag
existe sintaticamente mesmo quando é a errada.

**Como se prova:** não precisou de teste sintético — assim que os alerts do
Dependabot foram ligados, o GitHub já reportou **25 vulnerabilidades reais**
nas árvores de `npm` (1 critical em `handlebars`, o resto entre high e
medium — `node-forge`, `lodash`, `flatted`, `underscore`, `qs`, `uuid`,
`jose`). Todas em dependências transitivas de ferramentas de dev/teste
(Playwright, mermaid, jsdom, newman) — nenhuma no caminho de execução da
aplicação em produção — e todas já têm correção publicada, então
`dependabot_security_updates` deve abrir PR sozinho para cada uma, sem
esperar o cron semanal. O Trivy do PR #3, rodando contra as mesmas quatro
árvores, não bloqueou: a severidade que ele atribui à mesma CVE de
`handlebars` não bateu com `CRITICAL` na base dele (GHSA e a base do Trivy
divergem ocasionalmente na classificação de uma CVE) — sinal de que as duas
ferramentas se complementam, não que uma substitui a outra. Confirmar
push-protection com um segredo de teste fica para quando a Fase 4 tornar
isso relevante (é quando credencial de verdade passa a existir perto do
repositório).

### Fase 3 — Containerização ✅ Entregue (02/09/2026)

1. `backend/votecomdados-api/Dockerfile`: multi-stage, build Maven no
   estágio de compilação, runtime `eclipse-temurin:21-jre-alpine`, conforme
   já especificado em [BACKEND.md § 7](BACKEND.md#7-empacotamento-e-deploy).
   `-XX:MaxRAMPercentage=75` no `ENTRYPOINT`, não deixado para a task
   definition lembrar. Usuário não-root. `backend/.dockerignore` novo —
   sem ele, `node_modules/` de `backend/postman` (73 MB) e todo `target/`
   entravam no contexto de build por padrão.
2. `backend/votecomdados-ingestion/Dockerfile`: mesmo padrão, sem exposição
   de porta (é uma task batch, não um service).
3. `.github/workflows/trivy-image.yml`: build das duas imagens em todo PR
   que toque `backend/**`, `trivy image` bloqueando CRITICAL com correção
   disponível (mesma política da Fase 2, mesma ferramenta), publica o SBOM
   (CycloneDX) de cada imagem como artefato do workflow (90 dias de
   retenção).
4. `dependabot.yml` ganhou os dois ecossistemas `docker` que a Fase 2 tinha
   deixado pendentes.

**Como se prova:** verificado localmente antes de commitar, não só na
descrição do plano — `docker build` das duas imagens; a API rodando contra
um Postgres real (rede Docker isolada, sem tocar no `compose.yml` da raiz)
aplicou as 13 migrations, respondeu `200` em `/api/v1/politicos/{id}` e
`{"status":"UP"}` em `/actuator/health/readiness`, rodou como usuário
`votecomdados` (não root) e desligou sem travar em `docker stop`. O
workflow em si ainda não foi exercitado contra um PR real com CVE
introduzida de propósito — fica para a primeira vez que o Trivy de imagem
tiver algo relevante para sinalizar, mesmo raciocínio da Fase 2.

### Fase 4 — Bootstrap do estado remoto do Terraform ✅ Entregue (04/09/2026)

Problema clássico de ovo-e-galinha: Terraform não pode gerenciar o próprio
backend de estado antes de esse backend existir. Este é o único pedaço do
plano que **não** é 100% IaC, por definição — documentado como runbook, não
escondido como se fosse automático. Por decisão D3b, foi o **owner** quem
rodou estes comandos, com um IAM user novo criado para isso
(`votecomdados-bootstrap`, policy escopada só a S3 do bucket de state e ao
provider OIDC — não root, não `AdministratorAccess`) — esta sessão nunca
segurou nenhuma credencial AWS.

**Achado real durante a execução:** o thumbprint que a AWS auto-preencheu
(`ab9d0263244dd0326eb67015705a667e79cfe998`) é **diferente** do valor que
este runbook fixava manualmente antes da revisão de 04/09
(`6938fd4d98bab03faadb97b34396831e3780aea1`) — confirmação concreta de que
remover o `--thumbprint-list` manual (documentado na referência da AWS CLI
como opcional desde que a validação por cadeia de CA confiável existe) não
era só uma limpeza cosmética: o valor antigo estava
desatualizado e teria sido aceito pela API sem erro, só falhando depois, na
primeira vez que o GitHub Actions tentasse assumir uma role de verdade
(Fase 6).

Bucket S3 (versionado, `BlockPublicAccess` total, criptografado), criado uma
vez via CLI. Lock nativo do próprio backend S3 (`use_lockfile = true`,
suportado desde o Terraform 1.10 — a versão instalada aqui é 1.12.2) — sem
tabela DynamoDB à parte, um recurso a menos para bootstrapar, escanear e
manter, sem trade-off real nesta escala de uso (um único operador nunca
aplicando em paralelo). O runbook exato, com os comandos prontos para copiar
e colar, vai em `infra/BOOTSTRAP.md` assim que os dados abaixo estiverem
confirmados:

| Dado | Para quê | Status |
|---|---|---|
| AWS Account ID | ARNs no provider Terraform, condição do trust policy do OIDC, nome único do bucket de state | ✅ confirmado — só em `infra/terraform.tfvars` (gitignored), nunca em markdown versionado |
| Região | `us-east-1` (decisão de 02/09/2026, custo sobre latência — ver [CUSTOS_INFRA_AWS.md § Premissas](CUSTOS_INFRA_AWS.md#premissas)) | ✅ confirmado |
| Domínio | `votecomdados.com.br` — Route 53 hosted zone (módulo `dns`, Fase 5) e certificado ACM do CloudFront | ✅ confirmado |
| Conta/organização e nome do repositório no GitHub | `felipebrunheroto/votecomdados` — condição `repo:felipebrunheroto/votecomdados:*` no trust policy do OIDC (módulo `iam`, Fase 5) | ✅ confirmado |
| E-mail para os alarmes de billing | assinante do tópico SNS dos alarmes de 50/80/100% (módulo `observabilidade`, Fase 5) | ✅ confirmado — mesmo tratamento do account ID: só em `terraform.tfvars`/segredo do GitHub, não em markdown |

**Como se prova:** executado, não só planejado. Bucket criado
(`votecomdados-terraform-state-<account-id>`, `us-east-1`) com
versionamento, criptografia AES256 e bloqueio de acesso público — os três
confirmados via `get-bucket-versioning`/`get-bucket-encryption`/
`get-public-access-block`, não só assumidos pelo silêncio dos comandos de
criação. Provider OIDC criado e confirmado via
`get-open-id-connect-provider`. `terraform init` contra um `main.tf`
descartável (só o bloco `backend "s3"`, sem recursos — os módulos reais
ainda são a Fase 5) terminou em "Terraform has been successfully
initialized!", com `use_lockfile=true`. Verificação de recuperação a partir
de uma segunda máquina fica adiada — sem necessidade prática ainda, com um
único operador.

### Fase 5 — Módulos Terraform ✅ Entregue (04/09/2026)

Uma única árvore, `infra/`, com os recursos que a tabela de mapeamento de
[ARQUITETURA.md § 2](ARQUITETURA.md#2-nível-2--diagrama-de-containers) já
define para AWS:

| Arquivo | Conteúdo |
|---|---|
| `networking.tf` | VPC pública, security groups, **sem** NAT Gateway |
| `compute.tf` | cluster ECS, service da API (2 tasks), task definition do worker, ALB |
| `database.tf` | RDS PostgreSQL single-AZ, PITR, `db.t4g.small`, credencial gerenciada pela AWS |
| `edge.tf` | S3 + CloudFront, Custom Error Response 403/404→200 |
| `security.tf` | WAF web ACL, regra rate-based — no ALB, não na CDN (ver nota) |
| `dns.tf` | Route 53 hosted zone + certificado ACM |
| `secrets.tf` | Secrets Manager para o pepper do HMAC |
| `scheduler.tf` | EventBridge Scheduler (cron diário de ingestão + snapshot mensal do RDS) |
| `observability.tf` | CloudWatch: os três alarmes de [ARQUITETURA.md § 9](ARQUITETURA.md#operação-um-operador-sob-demanda-sem-plantão) como recursos, não só como prosa |
| `iam.tf`, `ecr.tf` | roles de execução ECS, role de deploy do GitHub Actions via **OIDC** |

**Desvio deliberado do desenho original:** "módulos Terraform" aqui virou
arquivos `.tf` no mesmo diretório, não módulos reutilizáveis
(`module { source = ... }`). Só existe um ambiente (produção, sem
staging), então a reutilização que módulos existem para dar não se aplica
— seria abstração sem consumidor, a mesma lição de "três linhas parecidas
é melhor que abstração prematura" já usada em outras decisões deste
projeto.

**Dois bugs reais achados por `terraform validate`, não hipotéticos:**

1. Ciclo de dependência entre `ecs_api` e `rds` (e depois `alb`/`ecs_api`):
   dois security groups com regra inline referenciando o `.id` um do
   outro. Resolvido com `aws_vpc_security_group_ingress_rule`/`egress_rule`
   como recursos avulsos, criados depois que ambos os SGs já têm ID
   conhecido — documentado inline em `networking.tf`.
2. Descrição de regra de security group com acento (`"HTTPS público"`)
   rejeitada pela própria validação de schema do provider AWS — o charset
   permitido não inclui letra acentuada nem travessão. Todas as
   descrições de SG neste arquivo foram reescritas sem acento.

**Gap novo, achado nesta fase:** `backend/votecomdados-ingestion/src/main/
resources/logback-spring.xml` usa texto puro
(`%d{...} %-5level [%thread] ... - %msgSeguro%n`), não JSON — apesar de
BACKEND.md § 3 descrever "Logback + logstash-logback-encoder". Afeta o
metric filter do alarme de "ingestão falhou 2 dias" (`observability.tf`),
que usa um padrão de texto simples (`pattern = "ERROR"`) em vez de um
filtro JSON — funciona, mas é outra divergência doc-vs-código para a lista
de follow-ups, ao lado da separação de credencial API/worker.

**Decisão pragmática de IAM, registrada por transparência:** a role de
deploy do GitHub Actions usa `PowerUserAccess` (tudo, exceto IAM/
Organizations) mais uma policy extra escopada só às ações de IAM que este
projeto precisa (`arn:aws:iam::*:role/votecomdados-*`). Least-privilege
ação-por-ação para uma role que aplica Terraform sobre
ECS+RDS+S3+CloudFront+WAF+Route53+Secrets Manager+EventBridge+CloudWatch+
IAM seria impraticável de manter à mão. A mitigação real continua sendo
D4 (aprovação manual do `apply`), não a granularidade da policy — ver
`infra/iam.tf` para o raciocínio completo.

**`trivy config .`: 8 achados, todos avaliados, não escondidos.** Cinco
suprimidos via `infra/.trivyignore.yaml` (mecanismo documentado do Trivy —
comentários inline `#trivy:ignore:` se mostraram inconsistentes nesta
versão, 0.74.0: funcionaram para 3 achados e não para outros 5 com
posicionamento idêntico, sem explicação encontrada na documentação
oficial). Os três que o `.trivyignore.yaml` também não suprimiu (AWS-0164,
AWS-0178, AWS-0136) permanecem visíveis no scan, mas têm a mesma
justificativa registrada no arquivo — nenhum é um risco não avaliado, só
uma inconsistência da ferramenta em aplicar a supressão.

**Como se prova:** `terraform fmt`/`validate` limpos (não precisam de
credencial AWS — só sintaxe e consistência interna). `trivy config .`
rodado e triado achado por achado. **`terraform plan`/`apply` ainda não
foram rodados contra a conta real** — por decisão D3b, esta sessão nunca
segurou credencial AWS, e a Fase 4 só criou o bucket de state e o provider
OIDC, não uma credencial ampla o bastante para aplicar toda essa
infraestrutura. Isso é trabalho da Fase 6 (pipeline) ou de uma primeira
aplicação manual guiada, ainda não feita — declarado em aberto, não
assumido como testado.

### Fase 6 — Pipeline de plan/apply ✅ Entregue (04/09/2026)

`.github/workflows/terraform.yml`:

- Em PR que altera `infra/**`: `terraform fmt -check`, `validate`, `plan`,
  resultado comentado automaticamente no PR (atualiza o mesmo comentário a
  cada push, não acumula um novo por commit) — o revisor (o próprio owner)
  lê o diff antes de aprovar.
- Em push para `main` que altera `infra/**`: `terraform apply`, atrás do
  ambiente `production` — criado com o owner como *required reviewer* e
  restrito a branches protegidas (só `main` qualifica, já que é a única
  com branch protection — Fase 0) — clique manual de confirmação, não
  automático (D4).
- Credencial de deploy: a role OIDC de `infra/iam.tf`, com o desvio de
  "escopada por tag/prefixo" já registrado na Fase 5 (`PowerUserAccess` +
  IAM escopado, não ação-por-ação).

**Ovo-e-galinha real, não hipotético:** este workflow autentica via a
mesma role OIDC que o Terraform da Fase 5 cria — que só existe depois do
primeiro `apply` bem-sucedido. Até lá, `plan`/`apply` aqui falham por
design, não por bug. `infra/PRIMEIRA_APLICACAO.md` é o runbook dessa
aplicação inicial (o owner roda, com o IAM user de bootstrap
temporariamente ampliado — mesma disciplina D3b da Fase 4: esta sessão
nunca segura credencial AWS). Só depois dela existir os quatro
vars/secrets do GitHub Actions (`AWS_ROLE_ARN`, `TF_STATE_BUCKET`,
`CPF_HMAC_PEPPER`, `BILLING_ALERT_EMAIL`) fazem sentido — `DOMINIO` já
está configurado, é público, sem essa dependência.

**Deliberadamente não promovido a check obrigatório ainda:** branch
protection continua só com `test`/`guards`/`build`/`newman` (Fase 1). Um
check `plan` que falha por credencial ausente bloquearia todo PR até a
primeira aplicação acontecer — entra na lista de required checks só
depois de confirmado rodando de ponta a ponta (último passo de
`infra/PRIMEIRA_APLICACAO.md`).

**Como se prova:** `actionlint` limpo antes de commitar. O resto —
comentário automático no PR, gate de aprovação do ambiente `production`
de fato bloqueando o `apply` — só é verificável depois da primeira
aplicação existir; fica registrado como pendente, não como testado.

### Fase 7 — Deploy da aplicação

Consome a infraestrutura da Fase 5/6:

- **Backend:** workflow builda a imagem (Fase 3), publica no ECR (já
  escaneada), atualiza a task definition do ECS.
- **Frontend:** implementa literalmente o pipeline já especificado em
  [FRONTEND.md § 9](FRONTEND.md#9-deploy-ci) — cron horário comparando
  watermark, `npm run build`, `sync --exclude 'dados-abertos/20*'`,
  invalidação de CDN, publicação de `build-info.json`.

**Como se prova:** os mesmos testes E2E já descritos em
[FRONTEND.md § 8](FRONTEND.md#8-testes) e a coleção Postman (Fase 1) rodando
contra o ambiente real, não só contra o `docker compose` local.

### Fase 8 — Guardrails de runtime e ir ao ar

Antes de começar a contar os 45 dias do plano de custo:

1. Forçar um gasto pequeno de propósito e confirmar que o alarme de billing
   de 50% dispara — alarme não testado é alarme que não existe.
2. Teste de restore do backup (RDS/Cloud SQL) — mecânica automatizável,
   verificação manual, dentro da janela dos 45 dias (nota já presente em
   ambos `CUSTOS_INFRA_*.md`).
3. Confirmar que a regra de rate limiting do WAF/Cloud Armor de fato
   bloqueia, com um teste de carga leve e controlado — não um DAST agressivo
   (ver § 8).

Só depois desse checklist o relógio dos 45 dias de produção começa a valer.

## 6. Ordem de execução e dependências

```
Fase 0 (repo) ──▶ Fase 1 (CI de qualidade) ──▶ Fase 2 (segurança de código)
                                                        │
                          Fase 3 (containers) ◀─────────┘
                                  │
   D3 (contas) ──▶ Fase 4 (bootstrap state) ──▶ Fase 5 (módulos Terraform)
                                                        │
                                            Fase 6 (pipeline plan/apply)
                                                        │
                                    Fase 7 (deploy app) ──▶ Fase 8 (go-live)
```

Fases 1 e 2 não dependem de D3 — podem começar imediatamente, mesmo antes de
ter conta AWS criada/confirmada. É onde recomendo começar.

## 7. Riscos

| Risco | Mitigação |
|---|---|
| D1 = privado (não público) reduz o que sai grátis do GitHub | Não se aplica — D1 já foi decidido como público |
| `terraform apply` manual (D4) atrasa deploys em caso de correção urgente | É a troca deliberada por segurança contra erro irreversível sem segunda pessoa revisando; o `plan` automático em todo PR já reduz a surpresa no momento do clique |
| Bootstrap do state (Fase 4) é o único passo não-IaC — risco de divergência se refeito à mão de novo | Documentado como runbook único, versionado em `infra/BOOTSTRAP.md`; não deveria rodar mais de uma vez |
| Custo de CI (minutos de Actions, execução de `docker compose` na Fase 1) | Grátis em repositório público (D1) |

## 8. O que este plano deliberadamente não inclui

- **Kubernetes** — decisão já tomada (ADR em ARQUITETURA.md § 11), não
  revisitada aqui.
- **Ambiente de staging** — decisão já tomada; rollout gradual (canary/
  blue-green) é a mitigação, não um ambiente isolado (nota em ambos
  `CUSTOS_INFRA_*.md`).
- **DAST automatizado em pipeline.** Sem staging, rodar um scanner ativo
  contra a própria produção a cada deploy é risco desnecessário contra o
  orçamento de CPU do banco que o WAF/Cloud Armor existe para proteger — o
  endpoint mais barato de atacar (`?q=`) é justamente o mais caro de servir.
  Um scan **passivo**, único, manual, antes do go-live, é o suficiente nesta
  escala; não entra em CI.
- **Multi-conta / Landing Zone (AWS Organizations, Control Tower).**
  Overhead de operação que um projeto de 45 dias com operador único não
  paga de volta.
- **Assinatura de imagem (cosign/sigstore) e admission control.** Valor real
  para uma frota de serviços; aqui são duas imagens, publicadas por um único
  pipeline confiável — possível próximo passo se o projeto crescer, não
  justificado agora.
- **Service mesh, WAF de terceiro além do nativo do provedor** — mesma razão
  do resto do projeto: nenhuma dependência de runtime nova sem tráfego que a
  justifique.
