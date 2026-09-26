terraform {
  required_version = ">= 1.12"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
    # Empacota a Lambda de busca de emendas a partir do .py versionado, sem
    # artefato binario no repositorio nem passo de build separado.
    archive = {
      source  = "hashicorp/archive"
      version = "~> 2.4"
    }
  }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Projeto       = "votecomdados"
      Ambiente      = "producao"
      GerenciadoPor = "terraform"
    }
  }
}
