variable "aws_region" {
  description = "Decisão D2/região de 02/09/2026 — custo sobre latência, ver CUSTOS_INFRA_AWS.md § Premissas."
  type        = string
  default     = "us-east-1"
}

variable "dominio" {
  description = "Domínio raiz do site (votecomdados.com.br)."
  type        = string
}

variable "billing_alert_email" {
  description = "E-mail assinante dos alarmes de billing (50/80/100%) e dos alarmes operacionais (5xx sustentado, ingestão falhou 2 dias)."
  type        = string
}

variable "github_repo" {
  description = "org/repo do GitHub, para a condição do trust policy do OIDC (Fase 6)."
  type        = string
  default     = "felipebrunheroto/votecomdados"
}

variable "imagem_api_tag" {
  description = "Tag da imagem votecomdados-api no ECR a ser implantada. `latest` só como default local — o pipeline real (Fase 7) sempre passa o SHA do commit."
  type        = string
  default     = "latest"
}

variable "imagem_ingestion_tag" {
  description = "Tag da imagem votecomdados-ingestion no ECR a ser implantada."
  type        = string
  default     = "latest"
}

variable "cpf_hmac_pepper" {
  description = "Segredo do HMAC do CPF (ver ARQUITETURA.md § 10 e CalculadoraDeHmac.java) — nunca tem default, a aplicação recusa subir sem ele. `sensitive = true` só oculta o valor da saída de `plan`/`apply`/`output` — o state em si guarda o valor em texto claro (Terraform não criptografa state por padrão); a proteção real é o bucket S3 criptografado + acesso restrito por IAM, não este atributo."
  type        = string
  sensitive   = true
}
