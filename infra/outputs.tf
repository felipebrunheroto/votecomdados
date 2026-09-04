output "cloudfront_domain" {
  value = aws_cloudfront_distribution.frontend.domain_name
}

output "alb_dns_name" {
  value = aws_lb.principal.dns_name
}

output "rds_endpoint" {
  value = aws_db_instance.principal.endpoint
}

output "rds_master_secret_arn" {
  value = aws_db_instance.principal.master_user_secret[0].secret_arn
}

output "ecr_api_repository_url" {
  value = aws_ecr_repository.api.repository_url
}

output "ecr_ingestion_repository_url" {
  value = aws_ecr_repository.ingestion.repository_url
}

output "route53_name_servers" {
  description = "Configurar estes NS no registro.br para o domínio apontar de fato para esta hosted zone."
  value       = aws_route53_zone.principal.name_servers
}

output "github_actions_deploy_role_arn" {
  description = "ARN a configurar como `AWS_ROLE_ARN` nas variáveis do GitHub Actions (Fase 6)."
  value       = aws_iam_role.github_actions_deploy.arn
}

output "frontend_bucket_name" {
  value = aws_s3_bucket.frontend.id
}
