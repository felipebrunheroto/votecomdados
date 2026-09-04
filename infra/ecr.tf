resource "aws_ecr_repository" "api" {
  name                 = "votecomdados-api"
  image_tag_mutability = "IMMUTABLE" # tag = SHA do commit (Fase 7) — nunca sobrescrita

  image_scanning_configuration {
    scan_on_push = true # redundante com o Trivy do CI (Fase 3), mas gratuito e é a última barreira antes do ECS puxar a imagem
  }
}

resource "aws_ecr_repository" "ingestion" {
  name                 = "votecomdados-ingestion"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_ecr_lifecycle_policy" "api" {
  repository = aws_ecr_repository.api.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "mantém só as 10 imagens mais recentes — sem isso o ECR acumula indefinidamente"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 10
      }
      action = { type = "expire" }
    }]
  })
}

resource "aws_ecr_lifecycle_policy" "ingestion" {
  repository = aws_ecr_repository.ingestion.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "mantém só as 10 imagens mais recentes"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 10
      }
      action = { type = "expire" }
    }]
  })
}
