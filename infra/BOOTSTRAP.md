# Bootstrap do estado remoto do Terraform (AWS)

Passo único, manual, rodado pelo **owner** — decisão D3b em
[docs/PLANO_DEVSECOPS_IAC.md § 3](../docs/PLANO_DEVSECOPS_IAC.md#3-decisões-do-owner).
Esta sessão de IA nunca segurou nem seguraria uma credencial AWS: os
comandos abaixo são para você copiar, colar e rodar com o acesso que já tem
na conta. A partir da Fase 5/6 do plano, tudo mais autentica via OIDC — sem
chave estática nenhuma.

## Por que este passo não é Terraform

Terraform guarda seu próprio estado remotamente (bucket S3, com lock nativo
do próprio backend) para não perder o controle da infraestrutura se a
máquina local sumir. Só que ele não pode gerenciar esse bucket com ele
mesmo — não existe `terraform apply` antes de o backend existir. É o único
pedaço deste projeto que fica fora de IaC, por definição, não por preguiça.

## Pré-requisitos

- AWS CLI instalado (`brew install awscli` no macOS) e autenticado com o
  acesso que você já tem na conta (`aws sts get-caller-identity` precisa
  responder sem erro antes de continuar).
- Permissão para criar bucket S3 e um IAM OIDC provider.

## 1. Exportar as variáveis desta sessão de terminal

**Não commitar isto em lugar nenhum** — são só variáveis de shell, vivem só
na sua máquina, nesta sessão de terminal:

```bash
export AWS_ACCOUNT_ID="<seu account id de 12 dígitos>"
export AWS_REGION="us-east-1"
export STATE_BUCKET="votecomdados-terraform-state-${AWS_ACCOUNT_ID}"
export GITHUB_REPO="felipebrunheroto/votecomdados"
export DOMINIO="votecomdados.com.br"
export BILLING_EMAIL="<o e-mail que vai receber os alarmes de billing>"
```

O nome do bucket precisa ser globalmente único entre **todas** as contas
AWS do mundo — por isso leva o account ID no nome, não porque o account ID
precise aparecer em algum lugar versionado (não precisa, e não deve).

## 2. Bucket S3 para o state do Terraform

```bash
aws s3api create-bucket \
  --bucket "$STATE_BUCKET" \
  --region "$AWS_REGION"

aws s3api put-bucket-versioning \
  --bucket "$STATE_BUCKET" \
  --versioning-configuration Status=Enabled

aws s3api put-public-access-block \
  --bucket "$STATE_BUCKET" \
  --public-access-block-configuration \
    BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true

aws s3api put-bucket-encryption \
  --bucket "$STATE_BUCKET" \
  --server-side-encryption-configuration \
    '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
```

> `us-east-1` é o único caso em que `create-bucket` **não** aceita
> `--create-bucket-configuration LocationConstraint=...` — é por isso que o
> comando acima não tem esse parâmetro. Em qualquer outra região, ele seria
> obrigatório.

Sem tabela DynamoDB de lock: o backend S3 do Terraform 1.10+ (a versão
instalada é 1.12.2) faz lock nativo via escrita condicional no próprio
bucket (`use_lockfile = true`), então este bootstrap para por aqui — um
recurso a menos para criar, escanear (Fase 2/5) e manter.

## 3. IAM OIDC provider para o GitHub Actions

Isso é o que deixa o GitHub Actions autenticar sem chave estática — sem
isso, a Fase 6 (pipeline de plan/apply) precisaria de um
`AWS_ACCESS_KEY_ID` guardado como secret do GitHub, exatamente o tipo de
credencial de longa duração que este plano existe para evitar.

```bash
aws iam create-open-id-connect-provider \
  --url "https://token.actions.githubusercontent.com" \
  --client-id-list "sts.amazonaws.com" \
  --thumbprint-list "6938fd4d98bab03faadb97b34396831e3780aea1"
```

> O thumbprint acima é o do certificado raiz atual do GitHub Actions OIDC.
> Confirmar contra a
> [documentação oficial da AWS](https://docs.aws.amazon.com/IAM/latest/UserGuide/id_roles_providers_create_oidc.html)
> antes de rodar — ele rotaciona ocasionalmente, e um valor desatualizado
> falha silenciosamente só na hora em que o GitHub Actions tentar assumir
> a role, não agora.

O `role` que este provider autoriza (com a condição
`repo:felipebrunheroto/votecomdados:*`) é criado pelo módulo `iam` do
Terraform na Fase 5 — não por este runbook. Este passo só cria o
**provider**, que é o pré-requisito de infraestrutura de conta que o
Terraform em si não pode criar sozinho (mesma razão do bucket de state).

## 4. Confirmar

Depois que o módulo raiz do Terraform (Fase 5) tiver um bloco `backend "s3"`
configurado, `terraform init` deve apontar para este bucket sem erro:

```bash
terraform init \
  -backend-config="bucket=$STATE_BUCKET" \
  -backend-config="region=$AWS_REGION" \
  -backend-config="key=votecomdados/terraform.tfstate" \
  -backend-config="use_lockfile=true"
```

Esse comando é o critério de "como se prova" da Fase 4 do plano — inclusive
rodado de uma segunda máquina, para confirmar que o estado é recuperado do
S3, não só local.

## O que NÃO fazer

- **Não commitar `terraform.tfvars`** com o account ID ou o e-mail de
  billing — ambos ficam fora do controle de versão (`.gitignore`, Fase 0).
- **Não rodar este runbook mais de uma vez.** Recriar o bucket do zero
  descartaria o histórico de versões do state, se algum já existir depois
  do primeiro `terraform apply`.
- **Não usar o `root` da conta** para rodar estes comandos, se evitável — um
  IAM user/role com permissão administrativa escopada já é preferível,
  mesmo para este bootstrap único.
