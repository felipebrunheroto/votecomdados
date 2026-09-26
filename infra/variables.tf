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

variable "portal_transparencia_chave" {
  description = "Chave da API de Dados da CGU, de onde vêm as emendas (ver docs/DISCOVERY_EMENDAS.md). Obtida em portaldatransparencia.gov.br/api-de-dados/cadastrar-email, com login gov.br nível Prata ou Ouro. Rotacionável sem impacto: autentica leitura e não deriva identificador guardado. Vale a mesma ressalva do pepper sobre `sensitive`: ele oculta o valor de plan/apply, mas o state guarda em texto claro — a proteção real é o bucket criptografado com acesso restrito por IAM."
  type        = string
  sensitive   = true
  # Sem default. Um default vazio pareceria inofensivo e não é: o `apply`
  # criaria o segredo VAZIO, o plan passaria verde, e a falha só apareceria
  # quando o job de emendas recusasse rodar — dias depois, longe da causa.
  #
  # A validação cobre o caso que "sem default" não pega sozinho: o workflow
  # passa `-var="portal_transparencia_chave="` quando o secret do GitHub não
  # existe, e string vazia é valor explícito, não ausência.
  validation {
    condition     = length(var.portal_transparencia_chave) > 0
    error_message = "portal_transparencia_chave vazia. Cadastre o secret PORTAL_TRANSPARENCIA_CHAVE em Settings > Secrets and variables > Actions. A chave sai de portaldatransparencia.gov.br/api-de-dados/cadastrar-email."
  }
}
