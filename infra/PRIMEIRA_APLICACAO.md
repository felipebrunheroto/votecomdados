# Primeira aplicação real

> **✅ Executada em 07/09/2026.** A infraestrutura existe, o pipeline de
> `plan` no CI autentica via OIDC e o `terraform plan` do CI reporta
> `No changes. Your infrastructure matches the configuration.` Este
> arquivo deixou de ser um roteiro do que fazer e passou a ser o registro
> do que foi feito — inclusive dos cinco problemas reais que só
> apareceram aqui, nenhum deles previsto no plano.

`.github/workflows/terraform.yml` (Fase 6) autentica via a role OIDC que
o próprio Terraform cria (`aws_iam_role.github_actions_deploy`, em
`infra/iam.tf`) — ovo-e-galinha clássico: essa role só existe depois do
primeiro `apply`, e o CI não tem como rodar esse primeiro `apply` sem ela
já existir. Por isso a aplicação inicial foi manual, rodada pelo **owner**
com o `votecomdados-bootstrap` temporariamente ampliado — mesma disciplina
D3b da Fase 4: nenhuma sessão de IA segurou credencial AWS em momento
algum.

## 1. Ampliar temporariamente o IAM user de bootstrap

No console, anexar ao `votecomdados-bootstrap` (além da policy que já tem):

- A policy gerenciada `PowerUserAccess`.
- Uma policy inline com o mesmo conteúdo de
  `data.aws_iam_policy_document.github_actions_iam_escopado` em
  `infra/iam.tf` (as ações de IAM escopadas a `role/votecomdados-*`).

> **Achado:** a primeira versão dessa lista não tinha
> `iam:UpdateAssumeRolePolicy`. Criar uma role com trust policy é
> `iam:CreateRole`; **alterar** a trust policy de uma role que já existe é
> outra ação — e a correção do OIDC (§ 5 abaixo) falhou por causa disso.
> `infra/iam.tf` já foi corrigido; se estiver copiando a lista de lá,
> está completa.

## 2. Preencher `terraform.tfvars`

```bash
cd infra
cp terraform.tfvars.example terraform.tfvars   # gitignored, nunca commitar
```

- `dominio` já vem certo no exemplo.
- `billing_alert_email`: o mesmo e-mail assinante do SNS.
- `cpf_hmac_pepper`: gerar **uma única vez** — `openssl rand -base64 32` —
  e nunca mais trocar (ver a nota em `infra/secrets.tf` sobre por quê).

## 3. Delegar o DNS antes de aplicar

**Faça isto primeiro, não por último.** O `aws_acm_certificate_validation`
fica em espera até o certificado ser emitido, e a AWS só emite depois de
enxergar o registro CNAME de validação pelo DNS **público** — o que exige
que o domínio já aponte para a hosted zone do Route53.

Na primeira aplicação isso não foi feito antes, e o resultado foi um
`apply` travado **1h15min** até estourar o timeout do recurso. Os
nameservers saem de `terraform output route53_name_servers` (ou, se o
apply falhar antes de emitir outputs, de
`terraform state show aws_route53_zone.principal`), e vão no registro.br
em "Alterar servidores DNS". Confirme a propagação antes de continuar:

```bash
dig NS votecomdados.com.br +short          # deve listar os 4 ns-*.awsdns-*
dig NS votecomdados.com.br @8.8.8.8 +short # confirmar por outro resolver
```

## 4. Aplicar

```bash
terraform init \
  -backend-config="bucket=$STATE_BUCKET" \
  -backend-config="region=us-east-1" \
  -backend-config="key=votecomdados/terraform.tfstate" \
  -backend-config="use_lockfile=true"

terraform plan   # ler antes de aplicar — é infraestrutura real, cobrada de verdade
terraform apply
```

> **Achado:** a conta AWS estava no **"Free plan"** (modelo que a AWS
> introduziu em 2025, escolhido no signup), que não libera
> `db.t4g.small` — o `apply` falhou com `FreeTierRestrictionError`. O
> primeiro erro dessa família reclamava do `backup_retention_period`, o
> que levou a um diagnóstico errado; a mensagem só ficou clara na segunda
> tentativa ("This instance size isn't available with free plan
> accounts"). A saída foi upgrade para o **Paid plan**
> (`console.aws.amazon.com/billing/home?#/freetier/upgrade`), que preserva
> os créditos. Vale notar que o teto de crédito do Free plan (US$ 100-200)
> já é menor que a própria estimativa de 45 dias deste projeto (~US$ 236,
> ver `docs/CUSTOS_INFRA_AWS.md`) — o Free plan nunca seria suficiente,
> independentemente do RDS.

## 5. Configurar o GitHub Actions com os outputs

```bash
terraform output -raw github_actions_deploy_role_arn
```

```bash
gh variable set AWS_ROLE_ARN --body "<o output acima>"
gh variable set TF_STATE_BUCKET --body "$STATE_BUCKET"
gh secret set CPF_HMAC_PEPPER --body "<o mesmo valor usado no terraform.tfvars>"
gh secret set BILLING_ALERT_EMAIL --body "<o e-mail>"
```

(`DOMINIO` já é configurado na Fase 6.)

> **Achado:** o primeiro `plan` do CI falhou com
> `Not authorized to perform sts:AssumeRoleWithWebIdentity`, mensagem
> genérica demais para diagnosticar. Quem respondeu foi o **CloudTrail**:
>
> ```bash
> aws cloudtrail lookup-events \
>   --lookup-attributes AttributeKey=EventName,AttributeValue=AssumeRoleWithWebIdentity \
>   --max-results 3 --query "Events[].CloudTrailEvent" --output text
> ```
>
> O `userName` do evento mostrou a claim `sub` real:
> `repo:felipebrunheroto@24783700/votecomdados@1354056011:pull_request` —
> o GitHub emite o `sub` com os **IDs numéricos imutáveis** do owner e do
> repositório embutidos, e a trust policy só cobria o formato antigo, por
> nome. Corrigido em `var.github_sub_patterns` (aceita os dois formatos).
> Fica a lição de método: quando o erro do lado do cliente é genérico, o
> CloudTrail tem o evento com o dado que falta.

## 6. Confirmar que o CI assume a role sozinho

Abrir um PR tocando `infra/**` e conferir que o job `plan` do workflow
`Terraform` roda e comenta o plan — sem erro de
`AssumeRoleWithWebIdentity`. Feito no PR #19: `plan` verde em 24s,
comentário publicado, resultado `No changes. Your infrastructure matches
the configuration.` (o que também prova que o state local e o que o CI
enxerga são o mesmo).

Só depois disso faz sentido promover `plan` a check obrigatório do branch
protection — ainda **não** promovido.

## 7. Reduzir as permissões do bootstrap de volta

Depois de tudo confirmado, **como root** (o próprio usuário não altera as
próprias permissões), em IAM → Users → `votecomdados-bootstrap` →
Permissions:

1. **Detach** `PowerUserAccess`
2. **Delete** a inline `votecomdados-primeira-aplicacao`
3. **Manter** só a `votecomdados-bootstrap-terraform` original

O usuário fica dormente: alcança o bucket de state e lê o provider OIDC,
nada além. Mudança de infraestrutura passa a sair só pelo CI, atrás da
aprovação do ambiente `production` (D4).

> **Por que não anexar `ReadOnlyAccess` para manter `terraform plan`
> local:** essa policy inclui `secretsmanager:Get*`, e esse wildcard cobre
> `GetSecretValue` — daria a uma access key de longa duração leitura
> permanente do pepper do HMAC de CPF e da senha master do RDS. O `plan`
> local não vale esse preço: o CI já roda `plan` em todo PR que toca
> `infra/**`. Se o OIDC quebrar, o caminho de recuperação é root +
> reampliar temporariamente — que seria necessário com `ReadOnlyAccess`
> também, já que ela não dá `iam:UpdateAssumeRolePolicy`.

## O que NÃO fazer

- Não reduzir a permissão do bootstrap user *antes* de confirmar que o
  apply terminou e que o CI consegue assumir a role — um apply pela
  metade sem permissão para terminar deixa o state inconsistente.
- Não commitar `terraform.tfvars` nem o pepper em lugar nenhum além do
  `secrets.CPF_HMAC_PEPPER` do GitHub.
- Não misturar bloco `ingress`/`egress` inline com
  `aws_vpc_security_group_*_rule` avulso no mesmo security group: um SG
  com qualquer regra inline numa direção passa a "possuir" aquela direção
  e **revoga** o que foi criado por fora. Custou um plano que apagaria a
  conectividade API→RDS (ver o comentário em `infra/networking.tf`).
