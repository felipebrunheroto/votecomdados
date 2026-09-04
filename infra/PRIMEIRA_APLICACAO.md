# Primeira aplicação real (fora do escopo automático da Fase 6)

`.github/workflows/terraform.yml` (Fase 6) autentica via a role OIDC que
o próprio Terraform cria (`aws_iam_role.github_actions_deploy`, em
`infra/iam.tf`) — ovo-e-galinha clássico: essa role só existe depois do
primeiro `apply`, e o CI não tem como rodar esse primeiro `apply` sem ela
já existir. Alguém com credencial mais ampla que o
`votecomdados-bootstrap` (Fase 4, escopado só a S3 + provider OIDC)
precisa aplicar uma vez, manualmente, fora do CI — o mesmo padrão da Fase
4, é o **owner** quem roda, nunca esta sessão de IA.

## 1. Ampliar temporariamente o IAM user de bootstrap

No console, anexar ao `votecomdados-bootstrap` (além da policy que já tem):

- A policy gerenciada `PowerUserAccess`.
- Uma policy inline com o mesmo conteúdo de
  `data.aws_iam_policy_document.github_actions_iam_escopado` em
  `infra/iam.tf` (as ações de IAM escopadas a `role/votecomdados-*`) —
  sem isso, `PowerUserAccess` sozinho não deixa criar as roles que o
  Terraform gerencia.

Depois da aplicação bem-sucedida, é seguro remover essas duas policies de
novo — o dia a dia passa a rodar via a role OIDC criada pelo próprio
apply, não por este user.

## 2. Preencher `terraform.tfvars`

```bash
cd infra
cp terraform.tfvars.example terraform.tfvars   # gitignored, nunca commitar
```

Editar `terraform.tfvars`:

- `dominio` já vem certo no exemplo.
- `billing_alert_email`: o mesmo e-mail configurado no SNS (Fase 4/5).
- `cpf_hmac_pepper`: gerar **uma única vez** — `openssl rand -base64 32` —
  e nunca mais trocar (ver a nota em `infra/secrets.tf` sobre por quê).

## 3. Aplicar

```bash
terraform init \
  -backend-config="bucket=$STATE_BUCKET" \
  -backend-config="region=us-east-1" \
  -backend-config="key=votecomdados/terraform.tfstate" \
  -backend-config="use_lockfile=true"

terraform plan   # ler antes de aplicar — é infraestrutura real, cobrada de verdade
terraform apply
```

## 4. Configurar o GitHub Actions com os outputs

```bash
terraform output -raw github_actions_deploy_role_arn
```

```bash
gh variable set AWS_ROLE_ARN --body "<o output acima>"
gh variable set TF_STATE_BUCKET --body "$STATE_BUCKET"
gh secret set CPF_HMAC_PEPPER --body "<o mesmo valor usado no terraform.tfvars>"
gh secret set BILLING_ALERT_EMAIL --body "<o e-mail>"
```

(`DOMINIO` já está configurado — ver `docs/PLANO_DEVSECOPS_IAC.md` § Fase 6.)

## 5. Confirmar que o CI consegue assumir a role sozinho

Abrir um PR trivial tocando `infra/**` (ex.: um comentário) e conferir que
o job `plan` do workflow `Terraform` roda e comenta o plan — sem erro de
`AssumeRoleWithWebIdentity`. Só depois disso faz sentido tratar `plan`
como check obrigatório do branch protection (ainda não é — ver
`docs/PLANO_DEVSECOPS_IAC.md` § Fase 6).

## O que NÃO fazer

- Não reduzir a permissão do bootstrap user *antes* de confirmar que o
  apply terminou com sucesso — um apply pela metade sem permissão para
  terminar deixa o state num estado inconsistente.
- Não commitar `terraform.tfvars` nem o pepper em lugar nenhum além do
  `secrets.CPF_HMAC_PEPPER` do GitHub.
