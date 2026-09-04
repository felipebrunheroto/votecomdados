# Sem NAT Gateway (decisão em ARQUITETURA.md § 9/§ 11 e CUSTOS_INFRA_AWS.md):
# ECS Fargate roda em subnet pública com IP público próprio, security group
# restrito a saída — evita o custo fixo de ~$32/mês do NAT Gateway. RDS
# fica nas mesmas subnets públicas, mas nunca é publicamente acessível
# (`publicly_accessible = false`), protegido por security group, não por
# isolamento de rede.
#
# Duas AZs: o ALB exige subnets em pelo menos duas — não é redundância
# multi-AZ de verdade (RDS continua single-AZ, decisão explícita, ver
# ARQUITETURA.md § 9 "Continuidade").

data "aws_availability_zones" "disponiveis" {
  state = "available"
}

locals {
  azs = slice(data.aws_availability_zones.disponiveis.names, 0, 2)
}

# trivy:ignore:AWS-0178 sem VPC Flow Logs: os security groups já são
# restritivos por construção (RDS só aceita das duas SGs de compute, a API
# não aceita nada fora do ALB) — o valor incremental de auditar tráfego
# leva a mais um CloudWatch Logs sem operador dedicado a revisá-lo
# regularmente. Revisitar se algum incidente real justificar a
# investigação em nível de pacote.
resource "aws_vpc" "principal" {
  cidr_block           = "10.20.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = "votecomdados" }
}

resource "aws_internet_gateway" "principal" {
  vpc_id = aws_vpc.principal.id

  tags = { Name = "votecomdados" }
}

resource "aws_subnet" "publica" {
  count             = 2
  vpc_id            = aws_vpc.principal.id
  cidr_block        = "10.20.${count.index}.0/24"
  availability_zone = local.azs[count.index]
  # trivy:ignore:AWS-0164 IP público é o preço explícito de não ter NAT
  # Gateway (ARQUITETURA.md § 9/§ 11, CUSTOS_INFRA_AWS.md) — sem ele, as
  # tasks Fargate (API e worker) não alcançariam ECR/internet para puxar a
  # imagem ou baixar dados de fonte, e RDS nunca fica exposto: ele continua
  # em `publicly_accessible = false`, protegido por security group, não
  # por isolamento de rede.
  map_public_ip_on_launch = true

  tags = { Name = "votecomdados-publica-${count.index}" }
}

resource "aws_route_table" "publica" {
  vpc_id = aws_vpc.principal.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.principal.id
  }

  tags = { Name = "votecomdados-publica" }
}

resource "aws_route_table_association" "publica" {
  count          = 2
  subnet_id      = aws_subnet.publica[count.index].id
  route_table_id = aws_route_table.publica.id
}

# --- Security groups ---

resource "aws_security_group" "alb" {
  name_prefix = "votecomdados-alb-"
  description = "Entrada publica 443 (WAF fica na frente da distribuicao, nao aqui); saida restrita a API."
  vpc_id      = aws_vpc.principal.id

  ingress {
    description = "HTTPS publico"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # Egress -> API fica fora deste bloco (regra cruzada com ecs_api, mesmo
  # motivo do ciclo entre ecs_api e rds — ver comentário mais abaixo).

  lifecycle { create_before_destroy = true }
  tags = { Name = "votecomdados-alb" }
}

resource "aws_security_group" "ecs_api" {
  name_prefix = "votecomdados-ecs-api-"
  description = "API: entrada so do ALB; saida so para RDS (nao precisa de internet - nao fala com fonte de governo nenhuma)."
  vpc_id      = aws_vpc.principal.id

  # A regra "ALB -> API" (ingress) vive fora deste bloco, como
  # aws_vpc_security_group_ingress_rule mais abaixo — colocá-la aqui
  # referenciando aws_security_group.alb.id enquanto o SG do ALB também
  # referencia este SG (para escopar o egress) criaria o mesmo ciclo de
  # dependência já explicado abaixo para ecs_api <-> rds.

  # A regra "API -> RDS" (egress) vive fora deste bloco, como
  # aws_vpc_security_group_egress_rule mais abaixo — colocá-la aqui
  # referenciando aws_security_group.rds.id enquanto o SG do RDS também
  # referencia este SG criaria um ciclo de dependência que o Terraform não
  # resolve (`Cycle: aws_security_group.rds, aws_security_group.ecs_api`).

  # HTTPS de saída (443) para o próprio Secrets Manager/ECR/CloudWatch —
  # sem VPC endpoint nesta escala (custo não se paga sozinho com só duas
  # tasks), então a chamada sai pela internet via o Internet Gateway da
  # subnet pública. Não dá para escopar por CIDR: os IP ranges dessas APIs
  # não são fixos nem publicados de um jeito prático de manter.
  # trivy:ignore:AWS-0104 aceito — ver justificativa acima.
  egress {
    description = "AWS APIs (ECR, Secrets Manager, CloudWatch Logs)"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  lifecycle { create_before_destroy = true }
  tags = { Name = "votecomdados-ecs-api" }
}

resource "aws_security_group" "ecs_ingestion" {
  name_prefix = "votecomdados-ecs-ingestion-"
  description = "Worker: sem entrada nenhuma (nao e um service, nao tem ALB); saida livre - precisa alcancar Camara/Senado/TSE/Alesp."
  vpc_id      = aws_vpc.principal.id

  # Egress livre, de propósito: o worker precisa alcançar portais de
  # governo (Câmara, Senado, TSE, Alesp) em domínios/IPs arbitrários, fora
  # do nosso controle — não há CIDR fixo pra escopar. Nenhuma entrada é
  # aceita (sem bloco `ingress` nenhum), o que já limita bastante a
  # superfície real deste SG.
  # trivy:ignore:AWS-0104 aceito — ver justificativa acima.
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  lifecycle { create_before_destroy = true }
  tags = { Name = "votecomdados-ecs-ingestion" }
}

resource "aws_security_group" "rds" {
  name_prefix = "votecomdados-rds-"
  description = "So aceita conexao dos dois security groups de compute - nunca da internet, mesmo estando em subnet publica. Regras de ingress/egress cruzadas ficam fora deste bloco (ver comentario abaixo)."
  vpc_id      = aws_vpc.principal.id

  lifecycle { create_before_destroy = true }
  tags = { Name = "votecomdados-rds" }
}

# --- Regras cruzadas entre alb <-> ecs_api <-> rds, como recursos avulsos ---
#
# Um SG com regra inline referenciando outro SG que por sua vez referencia
# o primeiro é um ciclo que o grafo de dependência do Terraform não
# resolve — os grupos acima ficam "vazios" de regras cruzadas, e essas
# regras entram aqui, depois que todos os SGs já têm ID conhecido.

resource "aws_vpc_security_group_egress_rule" "alb_para_api" {
  security_group_id            = aws_security_group.alb.id
  referenced_security_group_id = aws_security_group.ecs_api.id
  description                  = "ALB -> API"
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
}

resource "aws_vpc_security_group_ingress_rule" "api_de_alb" {
  security_group_id            = aws_security_group.ecs_api.id
  referenced_security_group_id = aws_security_group.alb.id
  description                  = "ALB -> API"
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
}

resource "aws_vpc_security_group_egress_rule" "api_para_rds" {
  security_group_id            = aws_security_group.ecs_api.id
  referenced_security_group_id = aws_security_group.rds.id
  description                  = "API -> Postgres"
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}

resource "aws_vpc_security_group_ingress_rule" "rds_de_api" {
  security_group_id            = aws_security_group.rds.id
  referenced_security_group_id = aws_security_group.ecs_api.id
  description                  = "API -> Postgres"
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}

resource "aws_vpc_security_group_ingress_rule" "rds_de_ingestion" {
  security_group_id            = aws_security_group.rds.id
  referenced_security_group_id = aws_security_group.ecs_ingestion.id
  description                  = "Worker -> Postgres"
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}

resource "aws_db_subnet_group" "principal" {
  name       = "votecomdados"
  subnet_ids = aws_subnet.publica[*].id

  tags = { Name = "votecomdados" }
}
