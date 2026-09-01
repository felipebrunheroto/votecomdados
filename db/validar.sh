#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Aplica db/schema.sql num Postgres limpo e roda db/test_invariantes.sql.
#
#   ./db/validar.sh
#
# Sai com código != 0 em qualquer erro de SQL ou invariante que falhe, o que o
# torna utilizável direto no CI.
#
# Nota: psql prefixa erros com "psql:arquivo:linha: ERROR:", então filtrar por
# "^ERROR" não encontra nada e o script "passa" com o schema quebrado — foi
# exatamente o que aconteceu durante o desenvolvimento. Aqui a verificação é
# pelo código de saída do psql (ON_ERROR_STOP=1), não por texto.
# ---------------------------------------------------------------------------
set -euo pipefail

CONTAINER="${CONTAINER:-vcd-validacao}"
IMAGEM="${IMAGEM:-postgres:16-alpine}"
RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

limpar() { docker stop "$CONTAINER" >/dev/null 2>&1 || true; }
trap limpar EXIT

echo "==> subindo $IMAGEM"
docker run -d --rm --name "$CONTAINER" \
    -e POSTGRES_PASSWORD=validacao -e POSTGRES_DB=votecomdados "$IMAGEM" >/dev/null

for _ in $(seq 1 30); do
    docker exec "$CONTAINER" pg_isready -U postgres >/dev/null 2>&1 && break
    sleep 2
done

docker cp "$RAIZ/db/schema.sql" "$CONTAINER:/tmp/schema.sql" >/dev/null
docker cp "$RAIZ/db/test_invariantes.sql" "$CONTAINER:/tmp/test.sql" >/dev/null
# Golden files: amostras reais das fontes, usadas pelos invariantes de
# mapeamento de voto (ver db/golden/README.md).
docker cp "$RAIZ/db/golden" "$CONTAINER:/tmp/golden" >/dev/null

echo "==> aplicando schema.sql"
docker exec "$CONTAINER" psql -U postgres -d votecomdados -q -v ON_ERROR_STOP=1 -f /tmp/schema.sql

echo "==> rodando invariantes"
saida=$(docker exec "$CONTAINER" psql -U postgres -d votecomdados -q -v ON_ERROR_STOP=1 -f /tmp/test.sql 2>&1)
echo "$saida" | grep -E "T[0-9]+ (OK|FALHOU)" | sed 's/^ *//'

if echo "$saida" | grep -q "FALHOU"; then
    echo "==> FALHOU: ao menos um invariante nao foi satisfeito" >&2
    exit 1
fi

total=$(echo "$saida" | grep -cE "T[0-9]+ OK")

# Um invariante cujo SELECT não retorna linha nenhuma não imprime NADA — nem
# OK nem FALHOU —, e passa despercebido: "não falhou" e "não rodou" ficam
# indistinguíveis. Foi o que aconteceu com o T20 ao trocar a chave de
# mapeamento da Alesp: o JOIN deixou de casar e o teste sumiu em silêncio.
#
# Aqui os declarados no arquivo são comparados com os efetivamente emitidos.
declarados=$(grep -oE "'T[0-9]+ (OK|FALHOU)" "$RAIZ/db/test_invariantes.sql" \
             | grep -oE "T[0-9]+" | sort -u)
emitidos=$(echo "$saida" | grep -oE "T[0-9]+ (OK|FALHOU)" | grep -oE "T[0-9]+" | sort -u)
mudos=$(comm -23 <(echo "$declarados") <(echo "$emitidos"))

if [ -n "$mudos" ]; then
    echo "==> FALHOU: invariante(s) declarado(s) que nao produziram resultado:" >&2
    echo "$mudos" | sed 's/^/    /' >&2
    echo "    (consulta sem linhas nao e aprovacao — e teste que nao rodou)" >&2
    exit 1
fi

# Segunda execução: o teste roda em transação com ROLLBACK, então precisa dar
# exatamente o mesmo resultado. Se algum valor fixo tivesse vazado para o
# banco, a contagem cairia aqui.
#
# stderr é descartado de propósito: T4, T6 e T9 provocam violação de
# constraint deliberadamente, e o erro do psql é a evidência de que a garantia
# funciona — não é falha.
echo "==> rodando invariantes de novo (verifica repetibilidade)"
saida2=$(docker exec "$CONTAINER" psql -U postgres -d votecomdados -q -f /tmp/test.sql 2>/dev/null)
total2=$(echo "$saida2" | grep -cE "T[0-9]+ OK")

if echo "$saida2" | grep -q "FALHOU" || [ "$total" -ne "$total2" ]; then
    echo "==> FALHOU: segunda execucao divergiu ($total vs $total2 invariantes)" >&2
    exit 1
fi

echo "==> OK: schema aplicado e $total invariantes verificados, duas vezes"
