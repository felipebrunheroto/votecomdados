# RDS single-AZ com PITR — decisão explícita (ARQUITETURA.md § 9
# "Continuidade"), não descuido: a janela de manutenção do provedor É
# indisponibilidade da API, aceitável porque o shell do perfil é estático
# e o stale-if-error da CDN segura o resto.
#
# `manage_master_user_password = true`: a AWS cria e gerencia um secret no
# Secrets Manager sozinha (rotação nativa incluída) — sem Terraform gerar
# senha, sem senha em variável nenhuma.
#
# GAP CONHECIDO, documentado em PLANO_DEVSECOPS_IAC.md § Fase 5: a API
# deveria ter credencial SELECT-only e o worker credencial de escrita
# (ARQUITETURA.md § 10), mas essa separação nunca foi implementada no
# banco — não existe migration criando esses dois roles Postgres. Por ora,
# API e worker usam a MESMA credencial master gerenciada abaixo. Corrigir
# isso é trabalho de aplicação (migration + wiring de credencial), não de
# infraestrutura — não bloqueia esta fase, mas fica registrado para não
# ser esquecido.
#
# Autenticação IAM do RDS ficaria bem melhor resolvida junto do mesmo gap
# acima (troca "credencial compartilhada" por "token de curta duração por
# role"), mas exige o datasource da aplicação saber pedir token IAM em vez
# de usuário/senha — mudança de código Java, não wiring de
# infraestrutura isolado. Mesmo follow-up, mesma decisão de não bloquear
# esta fase.
# trivy:ignore:AWS-0176
resource "aws_db_instance" "principal" {
  identifier     = "votecomdados"
  engine         = "postgres"
  engine_version = "16"

  instance_class    = "db.t4g.small"
  allocated_storage = 30
  storage_type      = "gp3"
  storage_encrypted = true

  db_name  = "votecomdados"
  username = "votecomdados_master"

  manage_master_user_password = true

  db_subnet_group_name   = aws_db_subnet_group.principal.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  publicly_accessible    = false
  multi_az               = false # single-AZ, decisão explícita

  backup_retention_period = 7             # PITR — ver ARQUITETURA.md § 9 "Continuidade"
  backup_window           = "06:00-06:30" # cedo, fora do horário provável de tráfego BR
  maintenance_window      = "sun:07:00-sun:07:30"

  # Fase 3 do desligamento: a protecao sai para o `destroy` poder rodar.
  #
  # Com ela ligada o destroy falha NO MEIO -- depois de ja ter apagado outros
  # recursos -- e deixa o state inconsistente, que e o pior resultado possivel
  # aqui. Por isso vira `false` num apply ANTES do destroy, e nao no mesmo.
  #
  # O `skip_final_snapshot = false` abaixo continua como estava, de proposito:
  # a destruicao deixa o snapshot `votecomdados-final` para tras. Ele e a rede
  # de seguranca enquanto o `pg_dump` de 06/10/2026 (217 MB, restore conferido
  # tabela por tabela contra a origem) nao estiver em segunda copia fora deste
  # laptop. Apagar o snapshot e passo separado, do nivel C.
  deletion_protection       = false
  skip_final_snapshot       = false
  final_snapshot_identifier = "votecomdados-final"

  tags = { Name = "votecomdados" }
}

# Snapshot mensal retido além dos 7 dias de PITR (ARQUITETURA.md §
# "Continuidade" e CUSTOS_INFRA_AWS.md) — EventBridge Scheduler dispara
# uma vez por mês; ver infra/scheduler.tf.
