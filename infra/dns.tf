resource "aws_route53_zone" "principal" {
  name = var.dominio
}

# Certificado único para o site (raiz + www) e a API (subdomínio) —
# validado por DNS na mesma hosted zone. us-east-1 já é a região decidida
# do projeto inteiro (ver CUSTOS_INFRA_AWS.md § Premissas), então este
# certificado serve tanto o CloudFront (que EXIGE us-east-1, sem exceção)
# quanto o ALB (que só precisa estar na mesma região do próprio ALB) — sem
# precisar de um segundo provider alias só para isso.
resource "aws_acm_certificate" "api" {
  domain_name               = var.dominio
  subject_alternative_names = ["www.${var.dominio}", "api.${var.dominio}"]
  validation_method         = "DNS"

  lifecycle { create_before_destroy = true }
}

resource "aws_route53_record" "validacao_certificado" {
  for_each = {
    for dvo in aws_acm_certificate.api.domain_validation_options : dvo.domain_name => {
      name  = dvo.resource_record_name
      type  = dvo.resource_record_type
      value = dvo.resource_record_value
    }
  }

  zone_id         = aws_route53_zone.principal.zone_id
  name            = each.value.name
  type            = each.value.type
  records         = [each.value.value]
  ttl             = 60
  allow_overwrite = true
}

resource "aws_acm_certificate_validation" "api" {
  certificate_arn         = aws_acm_certificate.api.arn
  validation_record_fqdns = [for r in aws_route53_record.validacao_certificado : r.fqdn]
}

resource "aws_route53_record" "site" {
  zone_id = aws_route53_zone.principal.zone_id
  name    = var.dominio
  type    = "A"

  alias {
    name                   = aws_cloudfront_distribution.frontend.domain_name
    zone_id                = aws_cloudfront_distribution.frontend.hosted_zone_id
    evaluate_target_health = false
  }
}

resource "aws_route53_record" "www" {
  zone_id = aws_route53_zone.principal.zone_id
  name    = "www.${var.dominio}"
  type    = "CNAME"
  ttl     = 300
  records = [var.dominio]
}

resource "aws_route53_record" "api" {
  zone_id = aws_route53_zone.principal.zone_id
  name    = "api.${var.dominio}"
  type    = "A"

  alias {
    name                   = aws_lb.principal.dns_name
    zone_id                = aws_lb.principal.zone_id
    evaluate_target_health = true
  }
}
