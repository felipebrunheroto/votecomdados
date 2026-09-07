# --- Cron diário: dispara o worker de ingestão (job INCREMENTAL) via
#     ecs:RunTask — sem ECS Service, sem fila, cobrado só pelo tempo de
#     execução (ver BACKEND.md § 4, ARQUITETURA.md § 5 "Modelo de
#     execução"). ---

data "aws_iam_policy_document" "scheduler_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["scheduler.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "scheduler_ingestao" {
  name               = "votecomdados-scheduler-ingestao"
  assume_role_policy = data.aws_iam_policy_document.scheduler_assume.json
}

data "aws_iam_policy_document" "scheduler_ingestao_permissoes" {
  statement {
    sid       = "RodarTaskDeIngestao"
    actions   = ["ecs:RunTask"]
    resources = [replace(aws_ecs_task_definition.ingestion.arn, "/:\\d+$/", ":*")]

    condition {
      test     = "ArnLike"
      variable = "ecs:cluster"
      values   = [aws_ecs_cluster.principal.arn]
    }
  }

  statement {
    sid     = "PassarRolesDaTask"
    actions = ["iam:PassRole"]
    resources = [
      aws_iam_role.execucao_ecs.arn,
      aws_iam_role.task_ingestion.arn,
    ]
  }
}

resource "aws_iam_role_policy" "scheduler_ingestao" {
  name   = "rodar-ingestao"
  role   = aws_iam_role.scheduler_ingestao.id
  policy = data.aws_iam_policy_document.scheduler_ingestao_permissoes.json
}

# O `coorte` é pré-requisito diário dos demais, não só da carga inicial
# (docs/BACKEND.md § "Tipos de job"): é ele que sincroniza a lista de
# candidatos de 2026 no TSE e poda quem saiu. Sem este cron, alguém que
# entra na coorte depois — registro deferido em recurso, substituição de
# chapa — nunca apareceria, e a poda nunca aconteceria. Faltava: a Fase 5
# só agendou o `incremental`.
#
# Uma hora antes do incremental, porque a ordem importa: votos e autoria
# referenciam `politico` por FK (docs/BACKEND.md § "Ordem de ingestão").
# Uma hora é folga generosa para um job que só lê o cadastro do TSE; se
# um dia não for, o worker tem exclusão mútua por fonte e o incremental
# falha visivelmente em vez de gravar dado órfão.
resource "aws_scheduler_schedule" "coorte_diaria" {
  name       = "votecomdados-coorte-diaria"
  group_name = "default"

  schedule_expression = "cron(0 5 * * ? *)" # 05:00 UTC = 02:00 BRT

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = "arn:aws:scheduler:::aws-sdk:ecs:runTask"
    role_arn = aws_iam_role.scheduler_ingestao.arn

    input = jsonencode({
      Cluster        = aws_ecs_cluster.principal.arn
      TaskDefinition = aws_ecs_task_definition.ingestion.family
      LaunchType     = "FARGATE"
      NetworkConfiguration = {
        AwsvpcConfiguration = {
          Subnets        = aws_subnet.publica[*].id
          SecurityGroups = [aws_security_group.ecs_ingestion.id]
          AssignPublicIp = "ENABLED"
        }
      }
      Overrides = {
        ContainerOverrides = [{
          Name    = "ingestion"
          Command = ["--job=COORTE"]
        }]
      }
    })
  }
}

resource "aws_scheduler_schedule" "ingestao_diaria" {
  name       = "votecomdados-ingestao-diaria"
  group_name = "default"

  # Horário de menor tráfego provável de leitores brasileiros — não é
  # requisito rígido, o cache de borda absorve o rebuild independente da
  # hora (ver FRONTEND.md § 1 "Pipeline de rebuild"). Roda depois do
  # `coorte` (05:00), que é seu pré-requisito.
  schedule_expression = "cron(0 6 * * ? *)" # 06:00 UTC = 03:00 BRT

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = "arn:aws:scheduler:::aws-sdk:ecs:runTask"
    role_arn = aws_iam_role.scheduler_ingestao.arn

    input = jsonencode({
      Cluster = aws_ecs_cluster.principal.arn
      # Família, não o ARN com revisão fixa: `RunTask` com só a família
      # roda a revisão ACTIVE mais recente. Com o ARN pinado, o cron
      # continuaria rodando para sempre a revisão que existia no último
      # `terraform apply` — ou seja, o worker nunca receberia uma imagem
      # nova publicada pelo pipeline de deploy (Fase 7) sem um apply
      # manual junto. A policy do scheduler já autoriza `:*` (qualquer
      # revisão da família), então não precisa mudar.
      TaskDefinition = aws_ecs_task_definition.ingestion.family
      LaunchType     = "FARGATE"
      NetworkConfiguration = {
        AwsvpcConfiguration = {
          Subnets        = aws_subnet.publica[*].id
          SecurityGroups = [aws_security_group.ecs_ingestion.id]
          AssignPublicIp = "ENABLED"
        }
      }
      Overrides = {
        ContainerOverrides = [{
          Name    = "ingestion"
          Command = ["--job=INCREMENTAL"]
        }]
      }
    })
  }
}

# --- Snapshot mensal do RDS, retido além dos 7 dias de PITR (ver
#     database.tf e ARQUITETURA.md § 9 "Continuidade") — via target
#     universal do Scheduler, sem Lambda. ---

resource "aws_iam_role" "scheduler_snapshot" {
  name               = "votecomdados-scheduler-snapshot"
  assume_role_policy = data.aws_iam_policy_document.scheduler_assume.json
}

data "aws_iam_policy_document" "scheduler_snapshot_permissoes" {
  statement {
    sid     = "CriarSnapshotMensal"
    actions = ["rds:CreateDBSnapshot"]
    resources = [
      aws_db_instance.principal.arn,
      "arn:aws:rds:${var.aws_region}:${data.aws_caller_identity.atual.account_id}:snapshot:votecomdados-mensal-*",
    ]
  }
}

resource "aws_iam_role_policy" "scheduler_snapshot" {
  name   = "criar-snapshot"
  role   = aws_iam_role.scheduler_snapshot.id
  policy = data.aws_iam_policy_document.scheduler_snapshot_permissoes.json
}

resource "aws_scheduler_schedule" "snapshot_mensal" {
  name       = "votecomdados-snapshot-mensal"
  group_name = "default"

  schedule_expression = "cron(0 7 1 * ? *)" # dia 1 de cada mês, 07:00 UTC

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = "arn:aws:scheduler:::aws-sdk:rds:createDBSnapshot"
    role_arn = aws_iam_role.scheduler_snapshot.arn

    input = jsonencode({
      DbInstanceIdentifier = aws_db_instance.principal.identifier
      # O sufixo com data entra no nome via $${aws.scheduler.execution-id}
      # do próprio Scheduler, não em Terraform: o nome final precisa ser
      # único por execução, algo que só é conhecido no momento do disparo.
      DbSnapshotIdentifier = "votecomdados-mensal-<aws.scheduler.execution-id>"
    })
  }
}
