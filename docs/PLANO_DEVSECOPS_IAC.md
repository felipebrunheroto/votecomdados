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

### Fase 0 — Fundações do repositório

Pré-requisito literal de tudo mais: sem `git`, não existe GitHub Actions.

1. `git init`, `.gitignore` (`target/`, `node_modules/`, `out/`,
   `.terraform/`, `*.tfstate*`, `.env*`, `.m2-cache/`).
2. Primeiro commit com o estado atual do repositório.
3. Criar o repositório remoto no GitHub (visibilidade conforme D1) e
   `git push`.
4. Branch protection em `main`: status checks obrigatórios (conforme forem
   existindo nas fases seguintes) antes de merge. Sem exigência de review
   de terceiro — não existe segundo revisor —, mas nenhum push direto sem
   os checks passarem.

**Como se prova:** `git log` mostra o histórico, o repositório aparece no
GitHub, e um PR de teste é bloqueado por branch protection até os checks
(ainda vazios nesta fase) serem satisfeitos.

### Fase 1 — CI de qualidade (sem nuvem, sem decisão de provedor pendente)

Tudo que hoje só roda manualmente (esta sessão inteira dependeu disso)
passa a rodar em todo PR e push para `main`. Zero infraestrutura de nuvem
envolvida — pode começar antes de D2/D3 estarem resolvidas.

| Workflow | O que roda | Origem |
|---|---|---|
| `.github/workflows/backend.yml` | `./mvnd clean test` (162 testes) | já existe, só nunca rodou em CI |
| `.github/workflows/db-guards.yml` | `db/validar-migrations.sh`, `db/validar.sh`, `node tools/validar-contrato.mjs` | já existem, mesma situação |
| `.github/workflows/frontend.yml` | `npm run typecheck`, `npm run lint`, `npm run build` (sem `NEXT_PUBLIC_API_URL`, valida que o export com fixtures não quebra) | já existem |
| `.github/workflows/postman.yml` | `docker compose up -d`, aguardar `/api/v1/politicos/...` responder, `npm run rodar` | reaproveita o `compose.yml` da raiz, validado nesta sessão |

**Como se prova:** um PR que quebra qualquer um desses (ex.: um teste
falhando de propósito) é bloqueado pelo branch protection da Fase 0.

### Fase 2 — Segurança de código e supply chain (ainda sem nuvem)

1. `dependabot.yml`: ecossistemas `maven`, `npm`, `github-actions` e
   `docker` (este último só passa a ter algo para escanear a partir da
   Fase 3).
2. `.github/workflows/codeql.yml`: análise Java + JavaScript/TypeScript,
   agendada + em todo PR.
3. Ativar secret scanning e push protection nas configurações do
   repositório (requer D1 = público, ou GHAS).
4. `.github/workflows/trivy-fs.yml`: `trivy fs` no backend e no frontend —
   CVE em dependência antes mesmo de existir imagem Docker.

**Como se prova:** introduzir de propósito uma dependência com CVE conhecida
num branch de teste e confirmar que o Dependabot/Trivy sinalizam antes do
merge; confirmar que um segredo de teste (chave falsa, formato reconhecível)
é bloqueado no `push`, não só denunciado depois.

### Fase 3 — Containerização

1. `backend/votecomdados-api/Dockerfile`: multi-stage, build Maven no
   estágio de compilação, runtime `eclipse-temurin:21-jre-alpine`, conforme
   já especificado em [BACKEND.md § 7](BACKEND.md#7-empacotamento-e-deploy).
   `-XX:MaxRAMPercentage=75` no `ENTRYPOINT`, não deixado para a task
   definition lembrar.
2. `backend/votecomdados-ingestion/Dockerfile`: mesmo padrão, sem exposição
   de porta (é uma task batch, não um service).
3. `.github/workflows/trivy-image.yml`: build das duas imagens em todo PR
   que toque `backend/**`, `trivy image` bloqueando CVE crítico sem patch
   disponível, `trivy image --format cyclonedx` publicando o SBOM como
   artefato do workflow.

**Como se prova:** `docker build` local das duas imagens sobe e responde
igual ao `docker compose` atual (mesmo comportamento, imagem por Dockerfile
em vez de `mvn spring-boot:run` num container Maven genérico); o workflow
falha se uma dependência com CVE crítico for introduzida de propósito.

### Fase 4 — Bootstrap do estado remoto do Terraform (passo manual único)

Problema clássico de ovo-e-galinha: Terraform não pode gerenciar o próprio
backend de estado antes de esse backend existir. Este é o único pedaço do
plano que **não** é 100% IaC, por definição — documentado como runbook, não
escondido como se fosse automático. Por decisão D3b, é o **owner** quem roda
estes comandos, com o acesso que já tem na conta — esta sessão não segura
nenhuma credencial AWS, nem temporária.

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

**Como se prova:** `terraform init` aponta para o backend remoto sem erro; um
segundo `terraform init` de outra máquina recupera o mesmo estado.

### Fase 5 — Módulos Terraform

Uma única árvore, `infra/`, com os módulos que a tabela de mapeamento de
[ARQUITETURA.md § 2](ARQUITETURA.md#2-nível-2--diagrama-de-containers) já
define para AWS:

| Módulo | Conteúdo |
|---|---|
| `networking` | VPC pública, security groups, **sem** NAT Gateway |
| `compute` | cluster ECS, service da API, task definition do worker |
| `banco` | RDS PostgreSQL single-AZ, PITR, `db.t4g.small` |
| `edge` | S3 + CloudFront, Custom Error Response 403/404→200 |
| `borda-de-seguranca` | WAF web ACL, regra rate-based |
| `dns` | Route 53 hosted zone |
| `segredos` | Secrets Manager (recursos declarados, **valores** injetados fora do state — `sensitive = true`, nunca em `.tfvars` versionado) |
| `scheduler` | EventBridge Scheduler (cron de ingestão) |
| `observabilidade` | CloudWatch: os três alarmes de [ARQUITETURA.md § 9](ARQUITETURA.md#operação-um-operador-sob-demanda-sem-plantão) (billing 50/80/100%, 5xx sustentado, ingestão falhou 2 dias) como recursos, não só como prosa |
| `iam` | roles de execução ECS com menor privilégio; role de deploy do GitHub Actions via **OIDC**, sem chave estática de longa duração |

Cada módulo é escaneado por `trivy config` no PR (Fase 2, mesma ferramenta,
sem tool nova).

**Como se prova:** `terraform plan` roda limpo; `terraform apply` num
ambiente de teste sobe uma stack completa; destruição (`terraform destroy`)
limpa tudo sem recurso órfão — testado antes de qualquer aplicação em conta
real de produção.

### Fase 6 — Pipeline de plan/apply

`.github/workflows/terraform.yml`:

- Em PR que altera `infra/**`: `terraform plan`, resultado comentado
  automaticamente no PR (o revisor — o próprio owner — lê o diff antes de
  aprovar).
- Em merge para `main`: `terraform apply`, atrás de um ambiente do GitHub
  (`production`) com o owner como *required reviewer* (decisão D4) — clique
  manual de confirmação, não automático.
- Credencial de deploy escopada por tag/prefixo do projeto — nunca
  `AdministratorAccess`/`roles/owner`.

**Como se prova:** um PR alterando `infra/**` mostra o `plan` como
comentário; aprovar o ambiente `production` é o único jeito de o `apply`
prosseguir — testado recusando a aprovação uma vez de propósito e
confirmando que nada muda na conta.

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
