# Busca das emendas parlamentares, em São Paulo.
#
# A API de Dados da CGU recusa requisição vinda de fora do Brasil. Medido em
# 26/09/2026, mesma URL e mesma chave:
#
#   máquina doméstica (residencial BR)   200
#   ECS em us-east-1                     504   (duas execuções, IPs distintos)
#   runner do GitHub Actions (EUA)       504
#   CloudShell em sa-east-1 (BR)         401   ← alcança a API
#
# O 504 vem ANTES da autenticação — sem chave também dá 504 de fora, quando o
# esperado seria 401. A recusa é na borda, e nenhum cabeçalho contorna:
# User-Agent de curl, de Java e ausente foram todos testados.
#
# Por que só a BUSCA muda de região, e não a ingestão inteira: o RDS e a VPC
# estão em us-east-1. Esta função precisa apenas de saída para a internet e de
# escrita no S3 — sem VPC, sem banco. O job de ingestão continua onde está e
# passa a ler o JSON do bucket, exatamente como já faz com o pacote do TSE.

provider "aws" {
  alias  = "sao_paulo"
  region = "sa-east-1"

  default_tags {
    tags = {
      Projeto       = "votecomdados"
      Ambiente      = "producao"
      GerenciadoPor = "terraform"
    }
  }
}

data "archive_file" "buscar_emendas" {
  type        = "zip"
  source_file = "${path.module}/lambda/emendas/buscar.py"
  output_path = "${path.module}/lambda/emendas/buscar.zip"
}

resource "aws_iam_role" "buscar_emendas" {
  provider           = aws.sao_paulo
  name               = "votecomdados-buscar-emendas"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume.json
}

data "aws_iam_policy_document" "lambda_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

data "aws_iam_policy_document" "buscar_emendas" {
  statement {
    sid       = "EscreverOJsonDoAno"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.ingestao.arn}/entrada/emendas-*.json"]
  }

  # O segredo vive em us-east-1 junto do resto. O ARN é qualificado por
  # região, então a leitura atravessa sem replicar nada — e replicar seria
  # criar uma segunda cópia da chave para manter em dia.
  statement {
    sid       = "LerAChaveDaCgu"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [aws_secretsmanager_secret.portal_transparencia_chave.arn]
  }

  statement {
    sid       = "EscreverLog"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.buscar_emendas.arn}:*"]
  }
}

resource "aws_iam_role_policy" "buscar_emendas" {
  provider = aws.sao_paulo
  name     = "buscar-emendas"
  role     = aws_iam_role.buscar_emendas.id
  policy   = data.aws_iam_policy_document.buscar_emendas.json
}

# Retenção explícita: sem o grupo declarado, a Lambda cria um sem expiração e
# o log vira acervo — o mesmo descuido que o bucket do frontend teve.
resource "aws_cloudwatch_log_group" "buscar_emendas" {
  provider          = aws.sao_paulo
  name              = "/aws/lambda/votecomdados-buscar-emendas"
  retention_in_days = 30
}

resource "aws_lambda_function" "buscar_emendas" {
  provider      = aws.sao_paulo
  function_name = "votecomdados-buscar-emendas"
  role          = aws_iam_role.buscar_emendas.arn
  handler       = "buscar.handler"
  runtime       = "python3.13"

  filename         = data.archive_file.buscar_emendas.output_path
  source_code_hash = data.archive_file.buscar_emendas.output_base64sha256

  # 900s é o teto da Lambda, e aqui é escolha, não preguiça: o spike mediu
  # 1,24s por página, então 421 páginas custam ~520s só de latência. Os 600s
  # que eu havia escrito vinham de um "~3min" de cabeça que contrariava a
  # minha própria medição — e uma execução de 27/09 ficou pendurada por mais
  # de uma hora, com a CLI reinvocando a cada estouro.
  timeout     = 900
  memory_size = 256

  environment {
    variables = {
      BALDE_INGESTAO    = aws_s3_bucket.ingestao.id
      SEGREDO_CHAVE_ARN = aws_secretsmanager_secret.portal_transparencia_chave.arn
    }
  }

  depends_on = [aws_cloudwatch_log_group.buscar_emendas]
}

# Invocacao assincrona NAO pode repetir aqui.
#
# O padrao da Lambda e reexecutar duas vezes quando a invocacao por evento
# falha -- desenhado para trabalho idempotente e curto. Esta funcao e o
# oposto: 421 paginas contra uma API de terceiro com teto de 400 req/min.
# Tres execucoes concorrentes do mesmo ano gastariam a cota tres vezes e
# podem SUSPENDER o token, que e a punicao que a CGU aplica.
#
# Sem retentativa, uma falha e uma falha -- e o workflow a mostra, com o log
# da funcao junto.
resource "aws_lambda_function_event_invoke_config" "buscar_emendas" {
  provider                     = aws.sao_paulo
  function_name                = aws_lambda_function.buscar_emendas.function_name
  maximum_retry_attempts       = 0
  maximum_event_age_in_seconds = 900
}
