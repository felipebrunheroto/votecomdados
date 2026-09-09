# Operação

Como rodar, acompanhar e consertar o VoteComDados em produção.

Este documento existe porque quase tudo o que quebrou em 07–09/09/2026 estava
entre o que a documentação afirmava e o que o código fazia. O que está aqui é
o que foi observado rodando, não o que se pretendia.

Para infraestrutura do zero, ver `infra/BOOTSTRAP.md` e
`infra/PRIMEIRA_APLICACAO.md`. Para os guardrails, `infra/FASE8.md`.

---

## 1. O que roda sozinho

| quando (UTC) | o quê | onde |
|---|---|---|
| 05:00 diário | COORTE do TSE (só 2026) | EventBridge |
| 06:00 diário | INCREMENTAL Câmara | EventBridge |
| 06:30 diário | INCREMENTAL Senado | EventBridge |
| 07:00 diário | INCREMENTAL Alesp + publica dados abertos | EventBridge |
| 07:00 dia 1º | snapshot mensal | EventBridge |
| de hora em hora | rebuild do site, **se o watermark mudou** | GitHub Actions |
| seg 06:00 | CodeQL | GitHub Actions |
| seg 06:23 | Trivy na imagem | GitHub Actions |
| seg 06:40 | Verificar guardrails | GitHub Actions |

**O cron do GitHub não é pontual.** Em repositório público a fila atrasa de
minutos a mais de uma hora; runs saem às :26, :04, :15. "De hora em hora" é
aproximado.

---

## 2. Carga inicial, na ordem

A ordem não é preferência: cada passo depende do anterior por chave
estrangeira ou por vínculo de identidade.

### 2.1 COORTE — quem são as pessoas

Sem isto, nada mais tem dono. Os pacotes do TSE precisam estar no bucket
privado (§ 4).

```
Actions → Rodar ingestão → Run workflow
  job     = COORTE
  fonte   = TSE
  arquivo = s3://votecomdados-ingestao-<conta>/entrada/consulta_cand_2014.zip
            s3://.../consulta_cand_2016.zip  ...  s3://.../consulta_cand_2026.zip
```

Separados por **espaço**, todos numa execução só. A ordem em que você digita
não importa — o job coloca o pacote de 2026 na frente sozinho.

**Por que todos juntos:** o `cpf_hmac` é o que costura a mesma pessoa entre
eleições (o `sq_candidato` muda a cada pleito), e ele é **expurgado ao fim de
cada execução**. Carregar 2022 numa execução separada cairia no casamento por
nome + nascimento, que é mais fraco — em 1,4 milhão de candidaturas, homônimo
com a mesma data de nascimento não é hipótese.

**Por que 2026 tem de estar na lista** mesmo já carregado: é ele que repõe o
`cpf_hmac` que o expurgo anterior levou. Sem ele, o job recusa — a poda
apagaria a base inteira.

Duração observada: **1h47** para 1,18 milhão de candidaturas (7 pacotes,
incluindo 3 municipais de ~450 mil cada).

### 2.2 BACKFILL — o que eles produziram

```
job   = BACKFILL
fonte = CAMARA
desde = 2024
ate   = 2024
```

**Não use `--ano`.** O backfill recorta por `desde`/`ate`; o job recusa `ano`
explicitamente, depois de um episódio em que ele foi ignorado em silêncio e
um pedido de "só 2024" virou 2001–2026.

Só CAMARA. Alesp publica a série inteira num arquivo (o INCREMENTAL já
carrega tudo) e o Senado reprocessa o ano a cada ciclo.

### 2.3 Frontend

O site só constrói depois que existe matéria: com `output: export`, o Next
recusa build se `generateStaticParams` devolver lista vazia. O workflow
confere isso antes de compilar e falha com o motivo escrito.

---

## 3. Como saber se deu certo

### Pelo log

`Actions → Logs da ingestão → Run workflow` (o parâmetro é em minutos). Existe
porque o usuário de bootstrap perdeu a permissão de ler log no passo 7 de
`infra/PRIMEIRA_APLICACAO.md`.

As linhas que importam, em ordem:

```
coorte: carregando consulta_cand_2026.zip
TSE consulta_cand_2026.zip: 20809 candidatura(s) em 28 arquivo(s) por UF
N candidatura(s) de eleicao anterior ignorada(s): a pessoa nao e candidata em 2026
poda: N pessoa(s) deixaram de ser candidatas em 2026
expurgo: cpf_hmac zerado em N registro(s)
execucao N concluida: N processados, N em quarentena
```

**Só a última linha significa fim.** Sem ela, a execução ficou
`EM_ANDAMENTO`, e o `cpf_hmac` não foi expurgado.

### Pela API, sem abrir o console

```bash
API=https://api.votecomdados.com.br/api/v1
curl -fsS "$API/meta/status" | jq -c '[.fontes[] | "\(.fonte):\(.status)"]'
curl -fsS "$API/politicos?tamanho=1" | jq '.pagination.total'
curl -fsS "$API/proposicoes" | jq '.ids | length'
```

Cuidado: **`/meta/status` só mostra fonte que já teve execução bem-sucedida.**
Uma fonte carregando pela primeira vez não aparece — ausência não é falha.

### Números de referência (09/09/2026)

| | |
|---|---|
| candidaturas processadas na carga multi-ano | 1.180.035 |
| pessoas na coorte de 2026 | 20.809 |
| deputados vinculados na 1ª execução do cadastro | 958 de 7.889 |
| proposições no pré-render | 49.401 |

O total de proposições na base **não é observável pela API**: `/proposicoes`
devolve só o que se pré-renderiza (legislatura corrente). Para o número
completo, conte no pacote de dados abertos:
`https://votecomdados.com.br/dados-abertos/AAAA-MM-DD/proposicao.csv`.

`fora da coorte` alto é o desenho funcionando: o projeto cobre só quem é
candidato em 2026.

---

## 3.1 Curadoria dos vínculos por similaridade

Quando o cadastro não acha a pessoa por id nem por nome + nascimento, ele casa
por **similaridade de nome** (limiar 0,85). Esses vínculos entram valendo, mas
marcados `FUZZY` e não revisados.

Isto não é higiene de dado: um vínculo errado **atribui voto e autoria de uma
pessoa a outra**, que é o pior erro que a plataforma pode cometer.

### Ver a fila

```
Actions → Rodar ingestão → curadoria = listar
```

Sai no log, do menos confiável para o mais:

```
score=0.8519 CAMARA:2319 LUCIANE PEREIRA DA SILVA (urna: LUCY DA SILVA)
```

O `job` e a `fonte` do formulário são ignorados quando `curadoria` está
preenchido — curadoria não é ingestão: não lê fonte, não move watermark e não
abre execução.

### Decidir

```
curadoria = aprovar   |  alvo = CAMARA:2319  |  revisor = <seu nome>
curadoria = rejeitar  |  alvo = CAMARA:2319  |  revisor = <seu nome>
```

**Rejeitar apaga o vínculo**, não o marca como "revisado e errado" — deixá-lo
manteria o dado errado em produção. A pessoa fica; o errado era o vínculo.

A ingestão seguinte vai tentar resolver aquele identificador de novo. Se cair
na mesma similaridade, **volta para esta fila** — a decisão não vira regra
automática, porque quem decide é gente.

O `revisor` é obrigatório porque o schema exige: a restrição
`revisao_auditavel` impede marcar revisado sem dizer quem e quando. Com curador
único isso importa mais, não menos — é o que separa curadoria auditável de
UPDATE manual em produção.

Alvo inexistente, já revisado ou determinístico **falha** em vez de reportar
sucesso: é preciso saber que não se aprovou nada.

### O tamanho real da fila

Em 09/09/2026 eram 128 vínculos por similaridade, mas o número engana:

| | |
|---|---|
| score 1,0 — nome idêntico | 114 |
| **abaixo de 1,0 — exigem julgamento** | **14** |

Os 114 entraram como fuzzy só porque a fonte não publica data de nascimento.
Nos 14 restantes o padrão é nome de urna apelidado (`LUCY DA SILVA` para
`LUCIANE PEREIRA DA SILVA`).

Para reproduzir essa contagem sem acesso ao banco, o pacote de dados abertos
basta:

```bash
curl -fsS https://votecomdados.com.br/dados-abertos/AAAA-MM-DD/identificador_externo.csv \
  | awk -F, 'NR>1 && $4=="FUZZY" && $5+0 < 0.9999'
```

---

## 4. Subir pacote do TSE

O CDN do TSE recusa requisição fora de navegador (403), então o download é
manual: `https://cdn.tse.jus.br/estatistica/sead/odsele/consulta_cand/consulta_cand_AAAA.zip`

Suba para **`votecomdados-ingestao-<conta>`**, prefixo **`entrada/`**.

Três detalhes que não são cosméticos:

1. **Nunca o bucket do frontend.** Ele é servido pelo CloudFront: o zip lá
   viraria URL pública com o CPF de ~20 mil pessoas.
2. **O prefixo `entrada/` é obrigatório** — o `GetObject` da task role é
   escopado nele, e a expiração de 90 dias filtra por ele. Fora dali, a
   ingestão falha com `AccessDenied` e nenhum outro sintoma.
3. **Manter a extensão `.zip`** — o leitor escolhe zip ou csv pela extensão.

Conferir depois de subir: a ETag do S3, quando não tem sufixo `-N`, é o MD5 do
arquivo. `md5 -q arquivo.zip` deve bater.

---

## 5. Quando falha

| sintoma | o que é | o que fazer |
|---|---|---|
| `execucao N falhou; watermark preservado` | falhou sem avançar o marcador | reler o log; nada foi corrompido |
| `HttpConnectTimeoutException` | portal da fonte instável | o baixador tenta 4×; se esgotar, redisparar |
| `fonte respondeu 404` | endereço errado ou arquivo removido | 4xx não é repetido de propósito |
| `arquivo(s) identicos aos de <ano-1>` | o portal serviu o corpo de outro ano | esperar e redisparar; carregar seria gravar dado errado |
| `ON CONFLICT DO UPDATE ... second time` | a fonte repete a mesma chave | defeito de carga, não de operação |
| exit code 137 | memória estourada | exit 143 é parada a pedido, não erro |
| `nenhuma candidatura de 2026 na base` | pacote do ano da coorte não foi incluído | incluir 2026 na lista |
| `EM_ANDAMENTO` órfão | task morreu sem encerrar | o reaper marca como FALHA no próximo início |
| build do site: `empty array from generateStaticParams` | não há matéria na base | rodar a ingestão antes |
| `ExpiredToken` no upload do site | build passou de 1h e a credencial venceu | credencial é pedida depois do build; se voltar, investigar |

**O site continua no ar durante qualquer falha de ingestão.** Ele é estático
no S3/CloudFront; a última publicação permanece servida.

---

## 6. Recarregar do zero

Não existe botão. A sequência é a mesma da § 2, e é idempotente — a coorte
casa por `(sq_candidato, ano)`, a carga da Câmara por `(casa, id_externo)`.
Reexecutar não duplica.

O que **não** é idempotente: o expurgo do `cpf_hmac`. Depois dele, a próxima
carga de eleição anterior precisa que o pacote de 2026 entre junto para
repor a âncora.

---

## 7. O que exige console AWS

O papel do GitHub Actions cobre quase tudo, mas não:

- **Subir arquivo para o bucket de ingestão** (§ 4).
- **Parar uma task travada**: Clusters → `votecomdados` → aba **Tasks** (não
  dentro do serviço — a ingestão é task avulsa) → filtrar por *Stopped*
  também, senão some ao terminar.
- **Confirmar a assinatura do SNS**: chega por e-mail e expira. Se ninguém
  clicar, **todo alarme do projeto fica mudo** — e o Terraform continua
  dizendo que está tudo certo. Ver `infra/FASE8.md`.
- **Ligar "Receive CloudWatch billing alerts"**: configuração de conta que o
  Terraform não alcança. Sem ela, os alarmes de custo ficam para sempre em
  `INSUFFICIENT_DATA`.

Para saber se a infraestrutura ainda bate com o código, sem inventar commit:
`Actions → Terraform → Run workflow` roda o `plan` (nunca o `apply`).
