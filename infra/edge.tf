# Um único bucket para o export estático do frontend E o pacote de dados
# abertos — dividem o prefixo `dados-abertos/` na mesma CDN, de propósito
# (ver FRONTEND.md § 9, nota sobre o `--exclude` do sync). Privado; só o
# CloudFront acessa, via Origin Access Control.

resource "aws_s3_bucket" "frontend" {
  bucket = "votecomdados-frontend-${data.aws_caller_identity.atual.account_id}"
}

data "aws_caller_identity" "atual" {}

resource "aws_s3_bucket_versioning" "frontend" {
  bucket = aws_s3_bucket.frontend.id
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_public_access_block" "frontend" {
  bucket                  = aws_s3_bucket.frontend.id
  block_public_acls       = true
  ignore_public_acls      = true
  block_public_policy     = true
  restrict_public_buckets = true
}

# SSE-S3 (chave da própria AWS), não uma CMK — aceito: este bucket só
# guarda o export estático do site e o pacote público de dados abertos (já
# licenciado CC BY 4.0, já publicado de propósito), nada sensível o
# bastante para justificar o custo recorrente de uma KMS key própria. RDS
# e Secrets Manager, que guardam o que de fato importa proteger, já usam
# criptografia com rotação gerenciada (ver database.tf, secrets.tf).
# trivy:ignore:AWS-0132
resource "aws_s3_bucket_server_side_encryption_configuration" "frontend" {
  bucket = aws_s3_bucket.frontend.id
  rule {
    apply_server_side_encryption_by_default { sse_algorithm = "AES256" }
  }
}

resource "aws_cloudfront_origin_access_control" "frontend" {
  name                              = "votecomdados-frontend"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

data "aws_iam_policy_document" "frontend_bucket" {
  statement {
    sid       = "SoCloudFront"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.frontend.arn}/*"]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.frontend.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "frontend" {
  bucket = aws_s3_bucket.frontend.id
  policy = data.aws_iam_policy_document.frontend_bucket.json
}

# Sem WAF aqui, de propósito: o único web ACL do plano (CUSTOS_INFRA_AWS.md
# — "1 web ACL") protege o ALB/API, não a CDN. O endpoint caro de atacar
# é a busca trigram (compute real por requisição); servir HTML estático
# já escala pela própria CDN sem custo incremental por requisição — não
# há o mesmo motivo para rate-limit aqui. Ver infra/security.tf.
#
# Sem access logging: o tráfego já é observável via CloudWatch (ALB access
# logs + métricas da API) — duplicar em S3 aqui é custo sem operador para
# de fato revisar os logs de borda regularmente nesta escala (um único
# operador, sem plantão — ARQUITETURA.md § 9).
# trivy:ignore:AWS-0011
# trivy:ignore:AWS-0010
resource "aws_cloudfront_distribution" "frontend" {
  enabled             = true
  default_root_object = "index.html"
  aliases             = [var.dominio, "www.${var.dominio}"]
  price_class         = "PriceClass_100" # só bordas América do Norte/Europa — tráfego alvo é BR, e mesmo assim essa classe cobre bem; a classe mais ampla custa mais sem ganho medido

  origin {
    domain_name              = aws_s3_bucket.frontend.bucket_regional_domain_name
    origin_id                = "s3-frontend"
    origin_access_control_id = aws_cloudfront_origin_access_control.frontend.id
  }

  default_cache_behavior {
    target_origin_id       = "s3-frontend"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true

    # Cache primário na borda, TTL de 1 dia — decisão de arquitetura (ver
    # ARQUITETURA.md § 7 "Por que não há cache in-memory atrás da API").
    # O Cache-Control real vem do próprio S3 object (definido no deploy,
    # Fase 7); estes valores são só o teto/piso quando o objeto não
    # especifica.
    forwarded_values {
      query_string = false
      cookies { forward = "none" }
    }
    min_ttl     = 0
    default_ttl = 86400
    max_ttl     = 604800
  }

  # A peça central do B2 (ver PLANO_CORRECAO_STATIC_PARAMS.md e a nota em
  # CUSTOS_INFRA_AWS.md): perfil de candidato sem pré-render vira 403/404
  # do S3 (objeto não existe) → CloudFront reescreve para 200 servindo
  # `/404.html` → o app reconhece `/politicos/{uuid}/` e busca o perfil no
  # navegador. Sem isso, a maioria dos ~28 mil candidatos fica inacessível.
  custom_error_response {
    error_code         = 403
    response_code      = 200
    response_page_path = "/404.html"
  }
  custom_error_response {
    error_code         = 404
    response_code      = 200
    response_page_path = "/404.html"
  }

  restrictions {
    geo_restriction { restriction_type = "none" }
  }

  viewer_certificate {
    acm_certificate_arn      = aws_acm_certificate_validation.api.certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }
}
