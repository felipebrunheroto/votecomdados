# Um web ACL regional, associado ao ALB — protege o endpoint que é barato
# de atacar e caro de servir (busca trigram, ver ARQUITETURA.md § 10). Não
# opcional: é o que impede um único cliente de esgotar os créditos de CPU
# burstable do RDS `db.t4g.small` (CUSTOS_INFRA_AWS.md, nota "O WAF é linha
# nova, exigida pela revisão de arquitetura").

resource "aws_wafv2_web_acl" "alb" {
  name  = "votecomdados-alb"
  scope = "REGIONAL"

  default_action {
    allow {}
  }

  rule {
    name     = "rate-limit-por-ip"
    priority = 1

    action {
      block {}
    }

    statement {
      rate_based_statement {
        limit              = 600 # requisições por IP a cada 5 min (janela fixa da AWS) — folga generosa sobre tráfego humano normal, aperta em varredura automatizada
        aggregate_key_type = "IP"
      }
    }

    visibility_config {
      sampled_requests_enabled   = true
      cloudwatch_metrics_enabled = true
      metric_name                = "votecomdados-rate-limit"
    }
  }

  # Conjunto gerenciado da própria AWS contra os ataques mais comuns
  # (SQLi, path traversal, etc.) — gratuito até um volume que este projeto
  # não alcança, e cobre casos que uma regra de rate limit sozinha não pega.
  rule {
    name     = "regras-gerenciadas-comuns"
    priority = 2

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesCommonRuleSet"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      sampled_requests_enabled   = true
      cloudwatch_metrics_enabled = true
      metric_name                = "votecomdados-regras-comuns"
    }
  }

  visibility_config {
    sampled_requests_enabled   = true
    cloudwatch_metrics_enabled = true
    metric_name                = "votecomdados-alb"
  }
}

resource "aws_wafv2_web_acl_association" "alb" {
  resource_arn = aws_lb.principal.arn
  web_acl_arn  = aws_wafv2_web_acl.alb.arn
}
