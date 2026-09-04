# Os três alarmes que ARQUITETURA.md § 9 "Operação" já definia como
# prosa — aqui viram recurso de verdade. Poucos, acionáveis, nenhum de
# madrugada: é a régua que decide o que entra aqui e o que não entra.

resource "aws_sns_topic" "alarmes" {
  name              = "votecomdados-alarmes"
  kms_master_key_id = "alias/aws/sns" # chave gerenciada pela AWS — sem custo adicional, ao contrário de uma CMK própria
}

resource "aws_sns_topic_subscription" "alarmes_email" {
  topic_arn = aws_sns_topic.alarmes.arn
  protocol  = "email"
  endpoint  = var.billing_alert_email
}

# --- Billing: 50/80/100% do teto de referência (~US$170/mês — ver
#     ARQUITETURA.md § 9 "Orçamento"; os 45 dias de CUSTOS_INFRA_AWS.md
#     equivalem a ~US$157/mês, então US$170 é a mesma folga já documentada,
#     não um número novo). ---
#
# PRÉ-REQUISITO FORA DO TERRAFORM: a métrica AWS/Billing só existe se
# "Receive Billing Alerts" estiver habilitado em Billing Preferences — é
# uma configuração de conta, não um recurso Terraform consegue ligar. Sem
# isso, os três alarmes abaixo ficam permanentemente em INSUFFICIENT_DATA,
# sem nunca disparar; verificar uma vez, manualmente, antes de confiar
# neles (ver Fase 8 do plano — "forçar um gasto pequeno de propósito").
#
# A métrica de billing só existe em us-east-1, mas como essa já é a região
# decidida do projeto inteiro, nenhum provider alias extra é necessário.

locals {
  teto_mensal_usd = 170
}

resource "aws_cloudwatch_metric_alarm" "billing_50" {
  alarm_name          = "votecomdados-billing-50pct"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "EstimatedCharges"
  namespace           = "AWS/Billing"
  period              = 21600 # 6h — a métrica de billing só atualiza a cada ~4-8h
  statistic           = "Maximum"
  threshold           = local.teto_mensal_usd * 0.5

  dimensions = { Currency = "USD" }

  alarm_actions = [aws_sns_topic.alarmes.arn]
  ok_actions    = [aws_sns_topic.alarmes.arn]
}

resource "aws_cloudwatch_metric_alarm" "billing_80" {
  alarm_name          = "votecomdados-billing-80pct"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "EstimatedCharges"
  namespace           = "AWS/Billing"
  period              = 21600
  statistic           = "Maximum"
  threshold           = local.teto_mensal_usd * 0.8

  dimensions = { Currency = "USD" }

  alarm_actions = [aws_sns_topic.alarmes.arn]
  ok_actions    = [aws_sns_topic.alarmes.arn]
}

resource "aws_cloudwatch_metric_alarm" "billing_100" {
  alarm_name          = "votecomdados-billing-100pct"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "EstimatedCharges"
  namespace           = "AWS/Billing"
  period              = 21600
  statistic           = "Maximum"
  threshold           = local.teto_mensal_usd

  dimensions = { Currency = "USD" }

  alarm_actions = [aws_sns_topic.alarmes.arn]
  ok_actions    = [aws_sns_topic.alarmes.arn]
}

# --- 5xx sustentado na borda (ALB) ---

resource "aws_cloudwatch_metric_alarm" "cinco_xx_sustentado" {
  alarm_name          = "votecomdados-5xx-sustentado"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 3
  datapoints_to_alarm = 3 # sustentado: 3 janelas seguidas, não um pico isolado
  metric_name         = "HTTPCode_Target_5XX_Count"
  namespace           = "AWS/ApplicationELB"
  period              = 300
  statistic           = "Sum"
  threshold           = 10

  dimensions = {
    LoadBalancer = aws_lb.principal.arn_suffix
  }

  treat_missing_data = "notBreaching"
  alarm_actions      = [aws_sns_topic.alarmes.arn]
  ok_actions         = [aws_sns_topic.alarmes.arn]
}

# --- Ingestão falhou 2 dias seguidos ---
#
# DOIS GAPS CONHECIDOS, documentados em PLANO_DEVSECOPS_IAC.md § Fase 5:
#
# 1. O worker não publica métrica de negócio nenhuma (nem Micrometer, nem
#    micrometer-registry-cloudwatch2 estão no classpath hoje, apesar de
#    descritos em BACKEND.md § 3 "Observabilidade e operação").
# 2. O log NÃO é JSON hoje — `backend/votecomdados-ingestion/src/main/
#    resources/logback-spring.xml` usa um `PatternLayoutEncoder` em texto
#    puro (`%d{...} %-5level [%thread] %logger{36} - %msgSeguro%n`), apesar
#    de BACKEND.md § 3 descrever "Logback + logstash-logback-encoder".
#
# Sem métrica de aplicação nem log estruturado, esta é uma APROXIMAÇÃO só
# de infraestrutura: um metric filter em texto simples (não filtro de JSON
# — `pattern = "ERROR"` casa a palavra em qualquer linha, é o que o
# %-5level realmente produz) conta ocorrências de `log.error(...)` no log
# group do worker (existem de verdade — ver SeletorDeJob.java, ex. "falha
# antes de abrir a execucao"), e o alarme dispara só se houver erro em 2
# janelas diárias seguidas — não confunde "uma falha isolada" (que
# ARQUITETURA.md § 9 diz se recuperar sozinha) com "dois dias seguidos"
# (que não). Prioridade de follow-up real: publicar a métrica de negócio
# de verdade (e, já que for mexer nisso, decidir se vale a pena também
# corrigir o log para JSON de verdade) e trocar este filtro por ela.

resource "aws_cloudwatch_log_metric_filter" "ingestao_erro" {
  name           = "votecomdados-ingestao-erro"
  log_group_name = aws_cloudwatch_log_group.ingestion.name
  pattern        = "ERROR"

  metric_transformation {
    name      = "IngestaoErros"
    namespace = "VoteComDados"
    value     = "1"
    unit      = "Count"
  }
}

resource "aws_cloudwatch_metric_alarm" "ingestao_falhou_dois_dias" {
  alarm_name          = "votecomdados-ingestao-falhou-2-dias"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  datapoints_to_alarm = 2
  metric_name         = aws_cloudwatch_log_metric_filter.ingestao_erro.metric_transformation[0].name
  namespace           = aws_cloudwatch_log_metric_filter.ingestao_erro.metric_transformation[0].namespace
  period              = 86400 # 1 dia
  statistic           = "Sum"
  threshold           = 0

  treat_missing_data = "notBreaching" # sem execução no dia = sem erro, não é falha
  alarm_actions      = [aws_sns_topic.alarmes.arn]
  ok_actions         = [aws_sns_topic.alarmes.arn]
}
