# Backend intencionalmente vazio — os valores (bucket, region, key) são
# passados via `-backend-config` no `terraform init`, nunca hardcoded aqui.
# O bucket embute o account ID no nome (globalmente único por natureza do
# S3), e este repositório é público (D1 em docs/PLANO_DEVSECOPS_IAC.md) —
# sem necessidade de publicar esse dado em código versionado.
#
# Ver infra/BOOTSTRAP.md para o bucket em si (criado manualmente, Fase 4,
# já entregue) e .github/workflows/terraform.yml (Fase 6) para como o CI
# preenche esses valores.
#
#   terraform init \
#     -backend-config="bucket=votecomdados-terraform-state-<account-id>" \
#     -backend-config="region=us-east-1" \
#     -backend-config="key=votecomdados/terraform.tfstate" \
#     -backend-config="use_lockfile=true"

terraform {
  backend "s3" {}
}
