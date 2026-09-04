# --- Execução das tasks ECS (padrão: puxar imagem do ECR, escrever logs,
#     ler os secrets que a task definition referencia) ---

data "aws_iam_policy_document" "ecs_tasks_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "execucao_ecs" {
  name               = "votecomdados-execucao-ecs"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy_attachment" "execucao_ecs_gerenciada" {
  role       = aws_iam_role.execucao_ecs.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "execucao_ecs_secrets" {
  statement {
    sid     = "LerSegredosDaTask"
    actions = ["secretsmanager:GetSecretValue"]
    resources = [
      aws_db_instance.principal.master_user_secret[0].secret_arn,
      aws_secretsmanager_secret.cpf_hmac_pepper.arn,
    ]
  }
}

resource "aws_iam_role_policy" "execucao_ecs_secrets" {
  name   = "ler-segredos"
  role   = aws_iam_role.execucao_ecs.id
  policy = data.aws_iam_policy_document.execucao_ecs_secrets.json
}

# --- Task role do worker de ingestão: única identidade da aplicação que
#     fala com uma API da AWS em runtime (publica o pacote de dados
#     abertos no S3 — ver ARQUITETURA.md § 8b). A API não precisa de task
#     role: só fala com o Postgres, que é autenticado por credencial, não
#     por IAM. ---

resource "aws_iam_role" "task_ingestion" {
  name               = "votecomdados-task-ingestion"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

data "aws_iam_policy_document" "task_ingestion_permissoes" {
  statement {
    sid       = "PublicarDadosAbertos"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.frontend.arn}/dados-abertos/*"]
  }
}

resource "aws_iam_role_policy" "task_ingestion" {
  name   = "publicar-dados-abertos"
  role   = aws_iam_role.task_ingestion.id
  policy = data.aws_iam_policy_document.task_ingestion_permissoes.json
}

# --- OIDC: GitHub Actions autentica sem chave estática (provider criado
#     manualmente na Fase 4, ver infra/BOOTSTRAP.md — Terraform não pode
#     criar o provider que autoriza o próprio Terraform a rodar). ---

data "aws_iam_openid_connect_provider" "github" {
  url = "https://token.actions.githubusercontent.com"
}

data "aws_iam_policy_document" "github_actions_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [data.aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    # `:*` de propósito, não travado por branch: PRs de outros branches só
    # rodam `plan` (Fase 6, permissão de leitura); só o job de `apply` do
    # workflow, atrás do ambiente `production` com aprovação manual (D4),
    # de fato muda algo.
    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repo}:*"]
    }
  }
}

resource "aws_iam_role" "github_actions_deploy" {
  name               = "votecomdados-github-actions-deploy"
  assume_role_policy = data.aws_iam_policy_document.github_actions_assume.json
}

# Sem AdministratorAccess (decisão registrada em PLANO_DEVSECOPS_IAC.md
# § Fase 6), mas honestamente: least-privilege ação-por-ação para uma role
# que roda `terraform apply` sobre ECS+RDS+S3+CloudFront+WAF+Route53+
# Secrets Manager+EventBridge+CloudWatch+IAM é impraticável de manter à
# mão sem reimplementar o que o IAM já oferece. O meio-termo real:
# PowerUserAccess (tudo, exceto gestão de usuário/grupo IAM e Organizations)
# + uma permissão extra e explícita para as poucas ações de IAM que este
# projeto precisa (as roles/policies que ele mesmo gerencia). O gatilho
# para apertar isso seria um segundo colaborador — hoje, o D4 (aprovação
# manual do apply) é a mitigação real, não a granularidade da policy.
resource "aws_iam_role_policy_attachment" "github_actions_poweruser" {
  role       = aws_iam_role.github_actions_deploy.name
  policy_arn = "arn:aws:iam::aws:policy/PowerUserAccess"
}

data "aws_iam_policy_document" "github_actions_iam_escopado" {
  statement {
    sid = "GerenciarRolesDoProjeto"
    actions = [
      "iam:CreateRole",
      "iam:DeleteRole",
      "iam:GetRole",
      "iam:PassRole",
      "iam:PutRolePolicy",
      "iam:DeleteRolePolicy",
      "iam:GetRolePolicy",
      "iam:AttachRolePolicy",
      "iam:DetachRolePolicy",
      "iam:TagRole",
      "iam:ListRolePolicies",
      "iam:ListAttachedRolePolicies",
    ]
    resources = ["arn:aws:iam::*:role/votecomdados-*"]
  }

  statement {
    sid       = "LerProviderOIDC"
    actions   = ["iam:GetOpenIDConnectProvider", "iam:ListOpenIDConnectProviders"]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "github_actions_iam_escopado" {
  name   = "iam-escopado-ao-projeto"
  role   = aws_iam_role.github_actions_deploy.id
  policy = data.aws_iam_policy_document.github_actions_iam_escopado.json
}
