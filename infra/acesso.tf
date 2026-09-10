# Log de acesso do CloudFront — SEM identificar quem acessou.
#
# A pergunta que motivou isto é "quantas pessoas visitam, e quais páginas
# interessam". As métricas do CloudWatch respondem só a primeira metade, e mal:
# contam requisição, e uma página estática carrega HTML mais chunks e fontes.
#
# O log padrão responderia as duas — mas o formato legado grava `c-ip`, e
# guardar o IP de quem consulta perfil de político contradiz um projeto que
# expurga CPF ao fim de cada coorte e recusou publicar foto de candidato.
#
# O logging v2 resolve a tensão em vez de administrá-la: ele deixa ESCOLHER os
# campos. Aqui não se coleta identificador nenhum do visitante — sem IP, sem
# user-agent, sem referer, sem cookie, sem ASN, sem país.
#
# A consequência é honesta e aceita: sem identificador, "visitantes únicos" se
# torna impossível de calcular. O que sobra é requisição por página, que é o
# que foi pedido — não quem, quantos.

resource "aws_s3_bucket" "acesso" {
  bucket = "votecomdados-acesso-${data.aws_caller_identity.atual.account_id}"
}

resource "aws_s3_bucket_public_access_block" "acesso" {
  bucket                  = aws_s3_bucket.acesso.id
  block_public_acls       = true
  ignore_public_acls      = true
  block_public_policy     = true
  restrict_public_buckets = true
}

# trivy:ignore:AWS-0132
# SSE-S3 e nao CMK, mesma decisao dos outros buckets: o conteudo nao tem dado
# pessoal por construcao, e uma CMK aqui so mudaria quem gerencia a rotacao,
# com mensalidade por chave.
resource "aws_s3_bucket_server_side_encryption_configuration" "acesso" {
  bucket = aws_s3_bucket.acesso.id
  rule {
    apply_server_side_encryption_by_default { sse_algorithm = "AES256" }
  }
}

# 30 dias: tempo de sobra para responder "o tráfego cresceu depois daquela
# entrevista?" e curto o bastante para o log não virar acervo por inércia.
# Mesmo sem dado pessoal, guardar para sempre o que ninguém vai consultar é
# custo e superfície sem contrapartida.
resource "aws_s3_bucket_lifecycle_configuration" "acesso" {
  bucket = aws_s3_bucket.acesso.id

  rule {
    id     = "expira-log-de-acesso"
    status = "Enabled"

    filter {}

    expiration {
      days = 30
    }
  }
}

# O serviço de entrega de logs escreve no bucket em nome da conta. As duas
# condições não são enfeite: `aws:SourceAccount` impede que a política sirva de
# ponte para outra conta escrever aqui.
data "aws_iam_policy_document" "acesso_entrega" {
  statement {
    sid    = "EntregaDeLogDoCloudFront"
    effect = "Allow"

    principals {
      type        = "Service"
      identifiers = ["delivery.logs.amazonaws.com"]
    }

    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.acesso.arn}/*"]

    condition {
      test     = "StringEquals"
      variable = "s3:x-amz-acl"
      values   = ["bucket-owner-full-control"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.atual.account_id]
    }
  }
}

resource "aws_s3_bucket_policy" "acesso" {
  bucket = aws_s3_bucket.acesso.id
  policy = data.aws_iam_policy_document.acesso_entrega.json
}

resource "aws_cloudwatch_log_delivery_source" "frontend" {
  name         = "votecomdados-frontend-acesso"
  log_type     = "ACCESS_LOGS"
  resource_arn = aws_cloudfront_distribution.frontend.arn
}

resource "aws_cloudwatch_log_delivery_destination" "acesso" {
  name          = "votecomdados-acesso-s3"
  output_format = "plain"

  delivery_destination_configuration {
    destination_resource_arn = aws_s3_bucket.acesso.arn
  }
}

resource "aws_cloudwatch_log_delivery" "frontend_acesso" {
  delivery_source_name     = aws_cloudwatch_log_delivery_source.frontend.name
  delivery_destination_arn = aws_cloudwatch_log_delivery_destination.acesso.arn

  # A lista é a decisão inteira deste arquivo. Cada campo ausente é uma
  # escolha: `c-ip`, `x-forwarded-for` e `c-port` identificariam a máquina;
  # `cs(User-Agent)` permite impressão digital; `cs(Referer)` e `cs(Cookie)`
  # vazam contexto de navegação; `c-country` e `asn` estreitam demais quando o
  # público de uma página é pequeno.
  record_fields = [
    "date",
    "time",
    "cs-uri-stem",        # qual pagina — o dado que motivou ligar isto
    "sc-status",          # separa 404 de visita de verdade
    "x-edge-result-type", # hit/miss: mostra se a borda esta absorvendo
  ]

  depends_on = [aws_s3_bucket_policy.acesso]
}
