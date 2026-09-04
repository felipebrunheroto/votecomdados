resource "aws_ecs_cluster" "principal" {
  name = "votecomdados"

  setting {
    name  = "containerInsights"
    value = "disabled" # custo extra sem valor comprovado nesta escala — reconsiderar se a observabilidade básica (Fase abaixo) não bastar
  }
}

resource "aws_cloudwatch_log_group" "api" {
  name              = "/ecs/votecomdados-api"
  retention_in_days = 30
}

resource "aws_cloudwatch_log_group" "ingestion" {
  name              = "/ecs/votecomdados-ingestion"
  retention_in_days = 30
}

# --- API: ECS Service, 24/7, atrás do ALB ---

resource "aws_ecs_task_definition" "api" {
  family                   = "votecomdados-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "512"  # 0,5 vCPU — CUSTOS_INFRA_AWS.md
  memory                   = "1024" # 1 GB
  execution_role_arn       = aws_iam_role.execucao_ecs.arn
  # Sem task_role_arn: a API não chama nenhuma API da AWS em runtime, só
  # fala com o Postgres via credencial (não IAM).

  container_definitions = jsonencode([{
    name  = "api"
    image = "${aws_ecr_repository.api.repository_url}:${var.imagem_api_tag}"

    portMappings = [{ containerPort = 8080, protocol = "tcp" }]

    # Graceful shutdown (application.yml: timeout-per-shutdown-phase=25s) —
    # o stopTimeout da task PRECISA ser maior, senão o ECS mata o processo
    # no meio da drenagem em todo deploy (ver BACKEND.md § 3, R6 em
    # REVISAO_ARQUITETURA.md).
    stopTimeout = 30

    environment = [
      { name = "DB_URL", value = "jdbc:postgresql://${aws_db_instance.principal.endpoint}/votecomdados" },
    ]

    secrets = [
      { name = "DB_USER", valueFrom = "${aws_db_instance.principal.master_user_secret[0].secret_arn}:username::" },
      { name = "DB_PASSWORD", valueFrom = "${aws_db_instance.principal.master_user_secret[0].secret_arn}:password::" },
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.api.name
        "awslogs-region"        = var.aws_region
        "awslogs-stream-prefix" = "api"
      }
    }
  }])
}

# Exposto à internet, de propósito — é a API pública do produto, sem
# autenticação de usuário final por desenho (ARQUITETURA.md § 10
# "Superfície pública mínima"). O WAF (infra/security.tf) é a mitigação
# real, não estar fora da internet.
# trivy:ignore:AWS-0053
resource "aws_lb" "principal" {
  name                       = "votecomdados"
  load_balancer_type         = "application"
  internal                   = false
  subnets                    = aws_subnet.publica[*].id
  security_groups            = [aws_security_group.alb.id]
  drop_invalid_header_fields = true
}

resource "aws_lb_target_group" "api" {
  name        = "votecomdados-api"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = aws_vpc.principal.id
  target_type = "ip" # obrigatório para Fargate awsvpc

  health_check {
    path                = "/actuator/health/readiness"
    healthy_threshold   = 2
    unhealthy_threshold = 3
    interval            = 15
    timeout             = 5
    matcher             = "200"
  }

  # Precisa ser maior que o stopTimeout da task (30s) — senão o ALB tira o
  # target de rotação depois do processo já ter sido morto, não antes.
  deregistration_delay = 45
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.principal.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = aws_acm_certificate.api.arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api.arn
  }
}

resource "aws_lb_listener" "http_redirect" {
  load_balancer_arn = aws_lb.principal.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"
    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

resource "aws_ecs_service" "api" {
  name            = "votecomdados-api"
  cluster         = aws_ecs_cluster.principal.id
  task_definition = aws_ecs_task_definition.api.arn
  desired_count   = 2 # CUSTOS_INFRA_AWS.md — 2 tasks
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = aws_subnet.publica[*].id
    security_groups  = [aws_security_group.ecs_api.id]
    assign_public_ip = true # sem NAT Gateway — a task precisa de IP público pra puxar a imagem do ECR
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.api.arn
    container_name   = "api"
    container_port   = 8080
  }

  depends_on = [aws_lb_listener.https]
}

# --- Worker de ingestão: ECS Task Definition só — sem Service, sem ALB.
#     Disparado pelo EventBridge Scheduler via RunTask (ver
#     infra/scheduler.tf). Mesma imagem serve o backfill histórico
#     (one-off, rodado manualmente uma vez) e o incremental diário. ---

resource "aws_ecs_task_definition" "ingestion" {
  family                   = "votecomdados-ingestion"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "512"
  memory                   = "1024"
  execution_role_arn       = aws_iam_role.execucao_ecs.arn
  task_role_arn            = aws_iam_role.task_ingestion.arn

  container_definitions = jsonencode([{
    name  = "ingestion"
    image = "${aws_ecr_repository.ingestion.repository_url}:${var.imagem_ingestion_tag}"

    environment = [
      { name = "DB_URL", value = "jdbc:postgresql://${aws_db_instance.principal.endpoint}/votecomdados" },
    ]

    secrets = [
      { name = "DB_USER", valueFrom = "${aws_db_instance.principal.master_user_secret[0].secret_arn}:username::" },
      { name = "DB_PASSWORD", valueFrom = "${aws_db_instance.principal.master_user_secret[0].secret_arn}:password::" },
      { name = "VOTECOMDADOS_CPF_PEPPER", valueFrom = aws_secretsmanager_secret.cpf_hmac_pepper.arn },
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.ingestion.name
        "awslogs-region"        = var.aws_region
        "awslogs-stream-prefix" = "ingestion"
      }
    }
  }])
}
