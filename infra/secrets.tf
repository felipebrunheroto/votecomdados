# O segredo do HMAC do CPF (ver ARQUITETURA.md § 10, CalculadoraDeHmac.java)
# — o valor em si vem de `var.cpf_hmac_pepper` (passado via -var no CI a
# partir de um GitHub Actions secret, nunca commitado). Terraform só cria o
# contêiner do segredo; o valor é gerado uma única vez fora daqui (ex.:
# `openssl rand -base64 32`) e nunca regenerado — trocar o pepper depois de
# a coorte já ter `cpf_hmac` calculado invalidaria todos os vínculos
# existentes.

resource "aws_secretsmanager_secret" "cpf_hmac_pepper" {
  name        = "votecomdados/cpf-hmac-pepper"
  description = "Pepper do HMAC de CPF — ver ARQUITETURA.md § 10. NUNCA rotacionar sem entender o impacto na coorte já processada."
}

resource "aws_secretsmanager_secret_version" "cpf_hmac_pepper" {
  secret_id     = aws_secretsmanager_secret.cpf_hmac_pepper.id
  secret_string = var.cpf_hmac_pepper
}
