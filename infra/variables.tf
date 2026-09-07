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

variable "github_sub_patterns" {
  description = <<-EOT
    Padrões aceitos para a claim `sub` do token OIDC do GitHub, na condição
    do trust policy da role de deploy (Fase 6).

    São DOIS formatos de propósito. O GitHub emite hoje o formato com IDs
    numéricos imutáveis do owner e do repositório embutidos
    (`repo:owner@<id>/repo@<id>:...`) — descoberto via CloudTrail na
    primeira aplicação real (07/09/2026), quando o `AssumeRoleWithWebIdentity`
    falhava com AccessDenied contra um padrão que só tinha os nomes. Esses
    IDs existem exatamente para que renomear (ou recriar com o mesmo nome)
    um repositório não herde o acesso da role; são informação pública, não
    segredo. O formato antigo, só por nome, fica na lista caso o GitHub
    volte a emiti-lo — ambos são escopados a este repositório e só a ele.
  EOT
  type        = list(string)
  default = [
    "repo:felipebrunheroto@24783700/votecomdados@1354056011:*",
    "repo:felipebrunheroto/votecomdados:*",
  ]
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
