#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Prova que db/schema.sql e as migrations Flyway produzem o MESMO banco.
#
#   ./db/validar-migrations.sh
#
# Existe porque as duas fontes divergiram silenciosamente uma vez: o schema
# ganhou tabelas novas e V1__init.sql ficou para trás. O build passava, porque
# os testes só enxergam o caminho das migrations — a divergência só apareceria
# em produção, como consulta contra coluna inexistente.
#
# A comparação é feita sobre `pg_dump --schema-only` de dois bancos no mesmo
# servidor, mais o conteúdo das tabelas de referência (mapeamento_voto,
# mapeamento_situacao, cobertura_fonte), que são dado versionado e não podem
# divergir tampouco.
# ---------------------------------------------------------------------------
set -euo pipefail

CONTAINER="${CONTAINER:-vcd-migrations}"
IMAGEM="${IMAGEM:-postgres:16-alpine}"
RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MIGRACOES="$RAIZ/backend/votecomdados-core/src/main/resources/db/migration"

limpar() { docker stop "$CONTAINER" >/dev/null 2>&1 || true; }
trap limpar EXIT

echo "==> subindo $IMAGEM"
docker run -d --rm --name "$CONTAINER" \
    -e POSTGRES_PASSWORD=validacao -e POSTGRES_DB=postgres "$IMAGEM" >/dev/null

for _ in $(seq 1 30); do
    docker exec "$CONTAINER" pg_isready -U postgres >/dev/null 2>&1 && break
    sleep 2
done

docker exec "$CONTAINER" psql -U postgres -q -c "CREATE DATABASE direto;"
docker exec "$CONTAINER" psql -U postgres -q -c "CREATE DATABASE migrado;"

echo "==> aplicando db/schema.sql em 'direto'"
docker cp "$RAIZ/db/schema.sql" "$CONTAINER:/tmp/schema.sql" >/dev/null
docker exec "$CONTAINER" psql -U postgres -d direto -q -v ON_ERROR_STOP=1 -f /tmp/schema.sql

echo "==> aplicando as migrations em 'migrado'"
for arquivo in $(ls "$MIGRACOES"/V*.sql | sort -V); do
    nome="$(basename "$arquivo")"
    echo "    $nome"
    docker cp "$arquivo" "$CONTAINER:/tmp/$nome" >/dev/null
    # Cada migration numa transação própria, como o Flyway faz.
    docker exec "$CONTAINER" psql -U postgres -d migrado -q -v ON_ERROR_STOP=1 \
        --single-transaction -f "/tmp/$nome"
done

echo "==> comparando estrutura"
# As linhas \restrict/\unrestrict que o pg_dump 16.15+ emite carregam um token
# aleatorio por execucao — ruido puro para esta comparacao.
for banco in direto migrado; do
    docker exec "$CONTAINER" pg_dump -U postgres --schema-only --no-owner \
        --no-privileges -d "$banco" \
        | grep -vE '^\\(un)?restrict ' > "/tmp/vcd-$banco.sql"
done

if ! diff -u /tmp/vcd-direto.sql /tmp/vcd-migrado.sql; then
    echo "==> FALHOU: schema.sql e as migrations divergiram (diff acima)" >&2
    exit 1
fi

echo "==> comparando dados de referência"
CONSULTA="
  SELECT 'mapeamento_voto', fonte::text, valor_origem, voto::text, coalesce(observacao,'')
    FROM mapeamento_voto
  UNION ALL
  SELECT 'mapeamento_situacao', fonte::text, valor_origem, situacao::text, conta_no_universo::text
    FROM mapeamento_situacao
  UNION ALL
  SELECT 'cobertura_fonte', esfera::text,
         coalesce(uf,'-')||'/'||coalesce(casa::text,'-')||'/'||recurso,
         status::text, coalesce(disponivel_desde::text,'-')
    FROM cobertura_fonte
  ORDER BY 1,2,3,4,5;"

for banco in direto migrado; do
    docker exec "$CONTAINER" psql -U postgres -d "$banco" -At -F'|' \
        -c "$CONSULTA" > "/tmp/vcd-dados-$banco.txt"
done

if ! diff -u /tmp/vcd-dados-direto.txt /tmp/vcd-dados-migrado.txt; then
    echo "==> FALHOU: dados de referência divergiram (diff acima)" >&2
    exit 1
fi

linhas=$(wc -l < /tmp/vcd-dados-direto.txt | tr -d ' ')
echo "==> OK: estrutura identica e $linhas linhas de referencia identicas"
