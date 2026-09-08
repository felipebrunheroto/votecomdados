# Fase 8 — Guardrails de runtime

Os três itens que o plano exige antes de o relógio dos 45 dias começar a
contar. A régua é a mesma da Fase 8: **alarme não testado é alarme que não
existe** — e o mesmo vale para backup nunca restaurado.

| item | como se verifica | estado |
|---|---|---|
| 1. Alarme de billing dispara | workflow **Verificar guardrails** | automatizado |
| 2. Restore do backup funciona | runbook abaixo, manual | manual, ~40 min |
| 3. Rate limit do WAF bloqueia | ver § 3 | ✅ verificado em 08/09/2026 |

---

## 1. Alarme de billing

O workflow **Verificar guardrails** (`.github/workflows/verificar-guardrails.yml`)
roda toda segunda 06:40 UTC e sob demanda. Ele checa as três coisas que podem
estar erradas sem nenhum sinal visível:

- **Assinatura de e-mail pendente.** A AWS manda um link de confirmação quando
  o tópico SNS é criado. Até alguém clicar, a assinatura fica
  `PendingConfirmation` — e nesse estado *todo* alarme deste projeto é mudo.
- **Métrica de billing inexistente.** `AWS/Billing` só existe com *Receive
  Billing Alerts* ligado em **Billing Preferences**. É configuração de conta;
  o Terraform não liga. Sem isso, os três alarmes de custo ficam para sempre
  em `INSUFFICIENT_DATA` e nunca disparam.
- **Backup sem snapshot.** Retenção configurada não é o mesmo que snapshot
  existente.

### Por que não "forçar um gasto de propósito"

O plano original dizia isso. Na prática não funciona:

- A métrica de billing só atualiza a cada ~6 horas — o laço de verificação
  seria de um dia.
- Cruzar 50% do teto de US$170 exigiria gastar **US$85 de verdade**, num
  projeto cujo orçamento inteiro é de ~US$236.

O que precisa ser provado é o **caminho da notificação**, e ele é idêntico
com gasto real ou com estado forçado. Por isso o teste usa
`cloudwatch set-alarm-state`, que é instantâneo e gratuito:

```
Actions → Verificar guardrails → Run workflow → testar_entrega = true
```

Devem chegar **duas** mensagens no e-mail de billing (ALARM e OK). Se não
chegarem, o problema está na entrega, não na regra — e nenhum alarme do
projeto funciona.

---

## 2. Teste de restore

**Não é automatizado de propósito.** Um restore cria uma instância nova, que
custa por hora; automatizá-lo sem supervisão é a receita para descobrir daqui
a duas semanas que ela ficou de pé. O passo de apagar é o mais importante
deste runbook.

Rode com o console aberto e reserve ~40 minutos — a maior parte é espera.

### 2.1 Escolher o snapshot

```bash
aws rds describe-db-snapshots --db-instance-identifier votecomdados \
  --snapshot-type automated \
  --query 'sort_by(DBSnapshots,&SnapshotCreateTime)[-1].[DBSnapshotIdentifier,SnapshotCreateTime]' \
  --output text
```

### 2.2 Restaurar para uma instância NOVA

Nunca sobre a instância de produção. O nome com sufixo deixa óbvio o que é
descartável:

```bash
aws rds restore-db-instance-from-db-snapshot \
  --db-instance-identifier votecomdados-teste-restore \
  --db-snapshot-identifier <o snapshot acima> \
  --db-instance-class db.t4g.micro \
  --no-multi-az \
  --no-publicly-accessible
```

`db.t4g.micro` e não a classe de produção: o que se prova aqui é que o
snapshot é restaurável e o dado está lá, não desempenho.

Espere ficar `available`:

```bash
aws rds wait db-instance-available --db-instance-identifier votecomdados-teste-restore
```

### 2.3 Conferir que o dado está lá

O restore "funcionou" só se o conteúdo bater. Do bastion/psql, contra a
instância restaurada:

```sql
SELECT count(*) FROM politico;
SELECT count(*) FROM candidatura;
SELECT count(*) FROM proposicao;
SELECT max(concluido_em) FROM ingestao_execucao WHERE status = 'CONCLUIDA';
```

Compare com produção. Diferença é esperada — o snapshot é de antes —, mas as
contagens devem ser da mesma ordem e a última execução deve ser anterior ao
snapshot, nunca posterior.

### 2.4 APAGAR (não pule)

```bash
aws rds delete-db-instance \
  --db-instance-identifier votecomdados-teste-restore \
  --skip-final-snapshot \
  --delete-automated-backups
```

Confirme que sumiu:

```bash
aws rds describe-db-instances --db-instance-identifier votecomdados-teste-restore
# esperado: DBInstanceNotFound
```

Registre a data do teste na tabela do topo deste arquivo.

---

## 3. Rate limit do WAF

**Verificado em 08/09/2026, sem querer.** Ao medir a distribuição de
proposições por ano, uma rajada de requisições à API levou **HTTP 403** — a
regra de rate limiting do WAFv2 bloqueou o cliente, exatamente como
desenhada.

Não é o teste de carga controlado que o plano previa, mas prova o que ele
queria provar: a regra existe, está ativa e bloqueia de fato. Um teste
sintético contra a própria produção acrescentaria risco sem acrescentar
informação — que é o mesmo argumento do § 8 do plano contra DAST agressivo.

Para reproduzir, se um dia for preciso:

```bash
for i in $(seq 1 300); do
  curl -s -o /dev/null -w "%{http_code}\n" https://api.votecomdados.com.br/api/v1/proposicoes &
done | sort | uniq -c
```

Espere ver `200` virando `403`. O bloqueio expira sozinho quando a taxa cai.
