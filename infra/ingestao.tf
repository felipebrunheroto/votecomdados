# Bucket PRIVADO das entradas da ingestão — separado do bucket do frontend
# por um motivo de segurança, não de organização.
#
# O pacote do TSE (`consulta_cand_*.zip`) contém `NR_CPF_CANDIDATO` de ~20
# mil pessoas (ver docs/REVISAO_ARQUITETURA.md B1). O bucket do frontend é
# servido pelo CloudFront: qualquer objeto ali vira URL pública. Colocar o
# zip lá exporia esses CPFs em
# `https://votecomdados.com.br/<qualquer-prefixo>/consulta_cand_2026.zip`.
#
# Este bucket não tem distribuição na frente, não tem policy de leitura
# para ninguém além da task role da ingestão, e bloqueia acesso público
# nos quatro flags.

resource "aws_s3_bucket" "ingestao" {
  bucket = "votecomdados-ingestao-${data.aws_caller_identity.atual.account_id}"
}

resource "aws_s3_bucket_public_access_block" "ingestao" {
  bucket                  = aws_s3_bucket.ingestao.id
  block_public_acls       = true
  ignore_public_acls      = true
  block_public_policy     = true
  restrict_public_buckets = true
}

# trivy:ignore:AWS-0132
# SSE-S3 e não CMK, mesma decisão do bucket do frontend (ver edge.tf) — mas
# aqui vale registrar a diferença: o conteúdo NÃO é público. A proteção que
# importa neste bucket é o bloqueio de acesso e a policy restrita, não a
# titularidade da chave; uma CMK aqui só mudaria quem gerencia a rotação, e
# custa mensalidade por chave.
resource "aws_s3_bucket_server_side_encryption_configuration" "ingestao" {
  bucket = aws_s3_bucket.ingestao.id
  rule {
    apply_server_side_encryption_by_default { sse_algorithm = "AES256" }
  }
}

# Guardar CPF por mais tempo do que o necessário é risco sem contrapartida
# (ARQUITETURA.md § 10: o próprio cpf_hmac é expurgado ao fim do COORTE) —
# mas este arquivo NÃO é descartável depois do primeiro uso: o cron diário
# do `coorte` o reprocessa todo dia para fazer a poda de quem saiu (ver
# scheduler.tf). Expirá-lo quebraria esse cron em silêncio.
#
# 90 dias: com folga além dos 45 dias de produção previstos
# (CUSTOS_INFRA_AWS.md), e ainda assim um limite — o arquivo não fica na
# conta para sempre por inércia. Se o projeto se estender, o número precisa
# ser revisto conscientemente, que é o ponto de existir um limite.
resource "aws_s3_bucket_lifecycle_configuration" "ingestao" {
  bucket = aws_s3_bucket.ingestao.id

  rule {
    id     = "expira-entradas-do-tse"
    status = "Enabled"

    filter {
      prefix = "entrada/"
    }

    expiration {
      days = 90
    }
  }
}
