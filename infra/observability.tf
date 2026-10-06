# Os três alarmes que ARQUITETURA.md § 9 "Operação" já definia como
# prosa — aqui viram recurso de verdade. Poucos, acionáveis, nenhum de
# madrugada: é a régua que decide o que entra aqui e o que não entra.

resource "aws_sns_topic" "alarmes" {
  name              = "votecomdados-alarmes"
  kms_master_key_id = "alias/aws/sns" # chave gerenciada pela AWS — sem custo adicional, ao contrário de uma CMK própria
}

# ATENCAO OPERACIONAL: assinatura de e-mail e fragil de um jeito que o
# Terraform nao protege.
#
# Ela exige confirmacao manual (a AWS manda um link), e QUALQUER pessoa que
# receba um alarme pode cancelar clicando "unsubscribe" no rodape da
# mensagem. O Terraform so percebe no proximo plan; ate la, todo alarme deste
# projeto e mudo -- dispara e nao avisa ninguem.
#
# Aconteceu em 09/09/2026: o plan das 21:52 do dia anterior refrescou esta
# assinatura com ARN real e reportou "No changes"; 15 horas depois o topico
# nao tinha assinatura nenhuma. Varios alarmes de falha de ingestao haviam
# disparado nesse intervalo.
#
# Por isso o workflow "Verificar guardrails" checa isto toda segunda, em vez
# de confiar em que estar no codigo signifique estar no ar.
resource "aws_sns_topic_subscription" "alarmes_email" {
  topic_arn = aws_sns_topic.alarmes.arn
  protocol  = "email"
  endpoint  = var.billing_alert_email
}

# --- Relatório diário: tópico SEPARADO do de alarmes, de propósito ---
#
# Alarme só funciona se for raro. O `votecomdados-alarmes` manda mensagem
# quando algo quebra — o que, correndo bem, é quase nunca. Pôr um relatório
# diário no mesmo canal faria a pessoa criar regra de filtro ou passar a
# arquivar sem ler, e no dia do alarme de verdade ele iria pelo mesmo caminho.
#
# Dois tópicos custam o mesmo (o SNS cobra por notificação, não por tópico, e
# 1.000 e-mails/mês são gratuitos — um relatório diário são ~30). O que se
# compra é que cada caixa de entrada mantenha seu significado: uma que se lê
# com calma, outra que se abre na hora.
resource "aws_sns_topic" "relatorio" {
  name              = "votecomdados-relatorio"
  kms_master_key_id = "alias/aws/sns"
}

resource "aws_sns_topic_subscription" "relatorio_email" {
  topic_arn = aws_sns_topic.relatorio.arn
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
  alarm_name = "votecomdados-5xx-sustentado"
  # Fase 0 do desligamento: para de NOTIFICAR, continua medindo. Com a API
  # fora, este alarme dispara corretamente — e aviso certo na hora errada é
  # ruído, que é o que ensina a ignorar e-mail de alarme. Os de BILLING ficam
  # ligados de propósito: são eles que confirmam a fatura caindo.
  actions_enabled = !var.desligado

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
# (que não). Esta última frase descrevia a INTENÇÃO e só passou a ser
# verdade em 30/09/2026: ver `default_value` no filtro abaixo, e o que o
# alarme de fato significava antes disso. Prioridade de follow-up real: publicar a métrica de negócio
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

    # A linha que faz o alarme significar o que o nome dele diz.
    #
    # Sem ela, o filtro só publica ponto QUANDO casa. Dia sem erro não vira
    # zero: vira ausência de dado. Medido em 30/09/2026, dez dias de métrica
    # tinham dois pontos ao todo:
    #
    #     2026-09-26T13:13  Soma = 2.0
    #     2026-09-28T13:13  Soma = 1.0
    #
    # O CloudWatch, para avaliar, busca pontos REAIS para trás até preencher
    # a janela. Achou esses dois, viu "2 de 2 acima do limite" e alarmou —
    # e eles nem são de dias consecutivos. `treat_missing_data` não salvava:
    # ele só age quando a busca para trás não acha pontos suficientes.
    #
    # Na prática o alarme significava "os dois últimos erros, quando quer que
    # tenham sido, foram erros" — verdade permanente depois do segundo erro
    # da vida do projeto. E não saía do estado, porque dia bom não gerava
    # ponto para contradizê-lo. Ficou em ALARM de 29/09 07:49 em diante com
    # UM erro em 72h (conferido no log: 28/09 e 30/09 com zero).
    #
    # Com `default_value = 0`, todo evento que NÃO casa publica zero. A
    # métrica fica densa, Sum do dia passa a ser a contagem de erros, e as
    # duas janelas diárias precisam de erro DE VERDADE para alarmar. Dia sem
    # erro nenhum agora contradiz o alarme, e ele sai sozinho.
    #
    # Dia sem NENHUMA linha de log continua sem ponto, e é isso que se quer:
    # "sem execução no dia" segue caindo em `treat_missing_data`.
    default_value = "0"
  }
}

resource "aws_cloudwatch_metric_alarm" "ingestao_falhou_dois_dias" {
  alarm_name = "votecomdados-ingestao-falhou-2-dias"
  # Fase 0 do desligamento: para de NOTIFICAR, continua medindo. Com a API
  # fora, este alarme dispara corretamente — e aviso certo na hora errada é
  # ruído, que é o que ensina a ignorar e-mail de alarme. Os de BILLING ficam
  # ligados de propósito: são eles que confirmam a fatura caindo.
  actions_enabled = !var.desligado

  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  datapoints_to_alarm = 2
  metric_name         = aws_cloudwatch_log_metric_filter.ingestao_erro.metric_transformation[0].name
  namespace           = aws_cloudwatch_log_metric_filter.ingestao_erro.metric_transformation[0].namespace
  period              = 86400 # 1 dia
  statistic           = "Sum"
  threshold           = 0

  # Só vale para o dia em que a ingestão não rodou (nenhuma linha de log,
  # logo nenhum ponto). Dia em que ela rodou e não errou agora publica zero
  # — ver `default_value` no filtro acima, e por que isso não era assim.
  treat_missing_data = "notBreaching"
  alarm_actions      = [aws_sns_topic.alarmes.arn]
  ok_actions         = [aws_sns_topic.alarmes.arn]
}
