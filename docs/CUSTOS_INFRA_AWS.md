# VoteComDados — Plano Financeiro de Infraestrutura (AWS)

Estimativa de custo para um único período de **produção de 45 dias corridos**
— sem piloto separado: o site sobe já em escala de produção (tráfego público
real, redundância mínima de API) e permanece no ar só pelos 45 dias
combinados, não indefinidamente.

**AWS é o provedor decidido (01/09/2026)** — ver
[PLANO_DEVSECOPS_IAC.md § 3](PLANO_DEVSECOPS_IAC.md#3-decisões-do-owner).
Existe um plano equivalente em [CUSTOS_INFRA_GCP.md](CUSTOS_INFRA_GCP.md),
mantido como referência de comparação, não como alternativa em aberto.

## Premissas

- **Região:** `us-east-1` (N. Virgínia) — **decisão deliberada de custo sobre
  latência**, confirmada pelo owner em 02/09/2026. A região previamente
  documentada aqui era `sa-east-1` (São Paulo), justamente pela menor
  latência ao usuário final brasileiro; `us-east-1` sai ~20-30% mais barata
  em compute/rede, ao custo de round-trip maior para o Brasil (tipicamente
  100-150ms a mais que `sa-east-1`), o que come parte do orçamento do alvo
  de TTFB p95 < 200ms de
  [ARQUITETURA.md § 9](ARQUITETURA.md#9-requisitos-não-funcionais) — a maior
  parte da resposta ainda sai da borda do CloudFront, não direto da região,
  então o efeito prático deve ficar concentrado nas rotas que batem no
  Postgres sem cache quente. O certificado ACM do CloudFront já teria que
  ser emitido em `us-east-1` de qualquer forma (exigência da AWS,
  independente de onde o resto da stack roda) — aqui a região inteira segue
  essa mesma exigência, não só o certificado.
- **Stack:** mapeamento direto dos containers do
  [diagrama C4 de nível 2](ARQUITETURA.md#2-nível-2--diagrama-de-containers) —
  ECS Fargate (API + worker de ingestão), RDS PostgreSQL, ALB,
  S3 + CloudFront, Route 53, Secrets Manager, EventBridge Scheduler.
- **Sem broker de fila:** o worker é um job batch fatiado por `(fonte, ano)`,
  disparado direto pelo scheduler — a arquitetura dispensa SQS, e a
  durabilidade vem da idempotência dos upserts (ver
  [ARQUITETURA.md § 5](ARQUITETURA.md#modelo-de-execução)).
- **Escopo pela coorte:** o banco guarda apenas as pessoas candidatas em 2026
  e o histórico delas, o que reduz o volume curado. Os CSVs anuais em massa
  são arquivados no object storage (imutáveis e reproduzíveis), não como
  JSONB no banco; o staging JSONB fica só para os payloads REST do
  incremental (ver [ARQUITETURA.md § 5](ARQUITETURA.md#a-coorte-de-2026-define-o-escopo)).
- **Sem NAT Gateway:** tasks Fargate rodam em subnet pública com security
  group restrito a saída (sem porta de entrada exposta além do ALB),
  evitando o custo fixo de ~$32/mês + processamento do NAT Gateway. Se a
  política de segurança exigir subnet privada, adicionar essa linha.
- **Direto para produção, sem fase piloto separada:** a configuração abaixo é
  a de tráfego público real desde o dia 1 — 2 tasks de API atrás do ALB,
  WAF ativo, banco dimensionado para uso real (`db.t4g.small`, não
  `t4g.micro`). Não existe uma segunda fase "de uso" com sizing maior: os 45
  dias são o período inteiro.
- **Tráfego do período:** cenário "pequeno / lançamento soft" — dezenas de
  milhares de visitas ao longo dos 45 dias, sem pico de mídia grande.
- **Valores em USD**, cotação de referência R$ 5,50 apenas para dar ordem
  de grandeza — a fatura real da AWS varia com câmbio do dia e com o modo
  de faturamento da conta (ver nota de impostos ao final).
- Preços são **estimativas de planejamento**, não cotação formal — validar
  no [AWS Pricing Calculator](https://calculator.aws) antes de comprometer
  orçamento.

---

## Os 45 dias

45 dias corridos ≈ 1,5 mês de 30 dias — os itens que cobram por
tempo-de-execução (compute, banco, LCUs, tráfego) estão escalados por esse
fator a partir do preço mensal de referência. Dois itens **não** escalam com
o prazo, porque não são cobrados por tempo de uso:

- **Worker de ingestão histórica** (~25h de Fargate): custo único, pago uma
  vez, antes do primeiro dia no ar — é o que carrega o histórico completo
  (proposições desde 1934, votos desde 2001) antes de o site ter algo para
  mostrar. Sem fase piloto, esse custo migra para dentro do próprio período
  de produção, mas continua sendo um evento único, não recorrente.
- **Registro de domínio**: registradores vendem em incrementos anuais — não
  existe "domínio por 45 dias". O valor abaixo é o ano inteiro, pago de uma
  vez, independente de o site ficar no ar 45 dias ou 365.

| Item | Configuração | USD (45 dias) |
|---|---|---:|
| ECS Fargate — API | 2 tasks, 0,5 vCPU / 1 GB cada, 24/7 | 55 |
| ECS Fargate — Worker de ingestão histórica | 0,5 vCPU / 1 GB, ~25h, único, antes do go-live | 2 |
| ECS Fargate — Worker de ingestão incremental | 0,5 vCPU / 1 GB, ~30h no período (cron diário) | 2 |
| RDS PostgreSQL | `db.t4g.small`, single-AZ, 30 GB gp3 (só a coorte de 2026) | 49 |
| Application Load Balancer | 1 ALB + LCUs | 29 |
| AWS WAF | 1 web ACL + rate-based rule (proteção da API pública) | 13 |
| Backup RDS | 7 dias de PITR + snapshot mensal retido | 2 |
| S3 (fotos + arquivo dos CSVs anuais) | ~12 GB | 3 |
| CloudFront (CDN) | tráfego real (~75-150 GB no período) — preço por borda/tier, não pela região de origem | 12 |
| Route 53 | hosted zone + queries — preço global, não varia por região | 2 |
| Secrets Manager | 3 secrets — preço global, não varia por região | 2 |
| CloudWatch (logs + métricas) | inclui métricas de negócio (quarentena, cobertura) | 8 |
| EventBridge Scheduler | cron de ingestão — preço global, não varia por região | 2 |
| Domínio (.org.br/.com.br) | registro anual — único, não reduz com o prazo de 45 dias, independe de região | 24 |
| **Subtotal** | | **≈ 205** |
| Margem de contingência (15%) | retries de ingestão, variação de tráfego | 31 |
| **Total (45 dias)** | | **≈ US$ 236 (~R$ 1.298)** |

---

## Notas importantes

- **A migração de `sa-east-1` para `us-east-1` (02/09/2026) foi recalculada
  por estimativa, não por cotação linha a linha.** Apliquei um desconto de
  ~20% (extremo mais conservador da faixa de 20-30% já documentada) nos
  itens de compute/rede que de fato variam por região — as duas linhas de
  ECS Fargate, RDS, ALB, backup e CloudWatch. WAF e S3 levaram um desconto
  menor (~10%, variação regional mais discreta para esses serviços).
  Route 53, Secrets Manager, EventBridge Scheduler e o domínio têm preço
  global, e não mudam com a região. CloudFront cobra pelo tier/borda de
  saída, não pela região de origem, e também não muda. **Antes de comprometer
  orçamento, validar os números reais no
  [AWS Pricing Calculator](https://calculator.aws) para `us-east-1`** — esta
  é uma estimativa de planejamento, igual ao resto do documento, só que
  ainda mais aproximada por não ter sido esta a região original do desenho.
- **Skipar o piloto tem um efeito colateral no Free Tier, não só uma
  economia de tempo.** Contas AWS novas ganham 750h/mês de RDS
  `t3/t4g.micro` de graça nos primeiros 12 meses — o antigo mês piloto usava
  exatamente essa classe. Como agora o banco já sobe em `db.t4g.small`
  (produção) desde o primeiro dia, essa faixa gratuita **não se aplica**: é o
  preço real de ir direto ao ar. Em compensação, o CloudFront tem 1 TB de
  saída grátis no free tier, o que cobriria os ~75-150 GB estimados no
  período inteiro — se a conta for nova, a linha de CloudFront acima pode
  cair a praticamente zero. S3 (5 GB grátis) reduz um pouco a linha de S3
  também. Confirmar a idade/uso prévio da conta antes de contar com isso.
- **Impostos:** o valor final da fatura depende de como a conta AWS está
  configurada. Cobrança via AWS Inc. (USD, cartão internacional) tende a
  sofrer IOF (atualmente ~5,38% em operações internacionais no cartão).
  Cobrança via AWS Brasil (BRL) tende a somar ISS + PIS/COFINS (referência
  histórica ~14-18% sobre o valor do serviço). Confirmar com o setor
  financeiro qual modalidade a conta usa antes de fechar o orçamento.
- **Sem staging:** como só existe produção, qualquer deploy arriscado
  durante os 45 dias deve usar rollout gradual (ex.: deployment ECS com
  `minimumHealthyPercent`/`maximumPercent` para rolling update, ou uma
  segunda target group para blue/green) em vez de depender de um ambiente
  de teste isolado — o custo dessa mitigação já está coberto pela margem
  de contingência acima, não exige recursos adicionais fixos.
- **O WAF é linha nova, exigida pela revisão de arquitetura.** A API é
  pública e anônima, e seu endpoint mais caro de servir (busca trigram) é o
  mais barato de atacar. Rate limiting por IP na borda custa o equivalente a
  ~US$ 8-9/mês e é o que impede que um único cliente esgote os créditos de
  CPU do banco burstable. Não é opcional, nem para um período curto.
- **O Redis foi removido, e é a maior economia do plano.** Com o cache HTTP
  de borda como camada primária ele já era candidato a corte; a definição de
  ~1.000 visitas/dia resolveu a dúvida antes da medição: espalhado por
  milhares de páginas, esse tráfego o deixaria tão frio quanto a borda, e um
  cache frio é só custo. Sai o equivalente a ~US$ 19/mês, um SPOF e uma
  dependência de runtime — mais do que o suficiente para pagar o WAF e a
  retenção de backup, que eram as duas mitigações que o orçamento original
  não comportava.
- **Backup é linha nova, decorrente da escolha por single-AZ.** Retenção de 7
  dias de PITR (incluída no RDS até o tamanho do banco) mais um snapshot
  mensal retido, a ~US$ 0,095/GB-mês: pouca coisa no tamanho previsto. O
  custo real do backup aqui não é dinheiro, é o **teste de restore**, que
  consome tempo de operação — e sem ele não há backup, há esperança. Vale a
  pena rodar esse teste pelo menos uma vez dentro dos 45 dias, não só
  planejar para depois.
- **Backfill: ~25h de worker.** A carga histórica usa os arquivos anuais em
  massa em vez de paginar a API REST registro a registro — sem isso, buscar
  todo o histórico (proposições desde 1934, votos desde 2001) seria inviável.
  A coorte não reduz o download, que é por ano inteiro; reduz o que se guarda.
- **Encerramento ao final dos 45 dias.** Este plano cobre só o período no ar;
  desligar não é gratuito de decidir. Antes de terminar os recursos:
  snapshot final do RDS (o backup automático não sobrevive à exclusão da
  instância), e decidir o destino dos instantâneos de dados abertos
  (`dados-abertos/20*`) — são citados como endereço permanente
  (ver [FRONTEND.md](FRONTEND.md)), então apagar o bucket depois do
  encerramento quebraria essas citações. Manter só o S3 com esses arquivos
  depois de desligar o resto (API, RDS, ALB) custa poucos dólares por mês e
  preserva a promessa de "instantâneo datado imutável" sem manter a conta de
  compute rodando. Decisão de produto, não coberta pelos números acima.
- **CloudFront precisa de uma Custom Error Response — sem isso a maioria dos
  perfis de candidato fica inacessível em produção.** A decisão de
  arquitetura do frontend (export estático, `output: "export"`) estava em
  aberto entre três saídas até 01/09/2026; a escolhida — fallback no
  hospedador — está implementada no código (`web/src/app/not-found.tsx`
  reconhece `/politicos/{uuid}/`, busca o perfil no navegador e renderiza a
  página de verdade quando o candidato não tem HTML pré-gerado, que é o caso
  da maioria dos ~28 mil candidatos, já que só quem tem atuação legislativa é
  pré-renderizado). A metade que falta é puramente de configuração da
  distribuição CloudFront: uma Custom Error Response para 403/404 devolvendo
  `/404.html` com status 200 (em vez do 403/404 padrão do S3), do jeito que
  qualquer SPA hospedada em S3+CloudFront resolve isso — **sem custo
  adicional**, é só uma regra na distribuição, mas sem ela o app nunca chega
  a rodar para esses perfis. Ver
  [PLANO_CORRECAO_STATIC_PARAMS.md](PLANO_CORRECAO_STATIC_PARAMS.md) e
  [FRONTEND.md](FRONTEND.md) para o histórico da decisão; nenhum IaC para
  isso existe no repositório ainda.
- **Se o prazo mudar, escala:** os números aqui assumem exatamente 45 dias de
  tráfego pequeno. Se surgir expectativa de pico dentro do período (ex.:
  cobertura de imprensa, proximidade da eleição), revisar `db.t4g.small` →
  `db.t4g.medium`, adicionar réplica de leitura no RDS e mais tasks Fargate
  atrás do ALB antes do evento, não durante. É também o ponto em que
  reintroduzir um cache atrás da API voltaria a fazer sentido — medindo
  antes, não por precaução. Se o período se estender além de 45 dias, o
  custo recorrente escala linearmente a partir da tabela acima (item por
  item, exceto os dois marcados como únicos).

## O que a fatura real mostrou (25/09/2026)

O plano acima é estimativa de antes de construir. Esta seção registra o
primeiro confronto com a fatura, porque a maior linha não era nenhuma das
previstas.

### S3 é a maior linha, e quase toda de requisição

| item | quantidade | custo |
|---|---|---|
| **Requests-Tier1** (PUT/COPY/POST/LIST) | **11.132.364** | **US$ 55,66** |
| Requests-Tier2 (GET) | 9.937 | US$ 0,00 |
| TimedStorage-ByteHrs | 19,212 GB-Mo | US$ 0,44 |
| *AWS Free Tier* | *crédito* | *(US$ 56,10)* |
| **líquido** | | **US$ 0,00** |

**99,2% do custo do S3 é PUT, não armazenamento.** Hoje o crédito cobre tudo
— e é por isso que os alarmes de billing ficam em `OK`. Quando o crédito
acabar, vira ~US$ 67/mês, de longe a maior linha do projeto.

### De onde vêm 11 milhões de PUT

Cada `aws s3 sync` do deploy do frontend escreve **294.701 objetos**:

```
250.175  proposicoes/<n>
 41.015  votacoes/<n>
  3.470  politicos/<id>
     ~40  páginas e assets
```

O `sync` compara tamanho e data de modificação; um `next build` novo regenera
tudo com mtime nova, então **todos** são reenviados, mesmo os que não mudaram.
Uma proposição de 2015 é reescrita a cada publicação.

11.132.364 ÷ 294.701 = **37,8 sincronizações completas em 25 dias**, ou ~1,5
por dia. O gatilho por watermark está funcionando: das 5 a 7 execuções
horárias do cron, a maioria pula corretamente; reconstroem as de merge e as de
mudança real de dado.

### Duas estimativas minhas que a fatura corrigiu

Vale registrar, porque o erro tem padrão:

- Estimei **48,6 milhões** de PUT/mês; o real projeta **13,4 milhões**. Assumi
  que toda execução do cron reconstruía, a partir de uma amostra de seis
  execuções tirada de um dia de merges — o dia menos representativo possível.
- Tratei o acúmulo de versões não-correntes como problema de custo. O
  armazenamento inteiro são **19,2 GB, US$ 0,44/mês**. A regra de ciclo de vida
  (`edge.tf`) continua certa como higiene — impede crescimento sem limite —,
  mas não era urgência financeira nenhuma.

### A decisão em aberto

**291 mil dos 294 mil objetos são páginas de proposição e votação.** Tirá-las
do pré-render corta 99% dos PUT. Elas já funcionam pelo fallback de cliente
(`web/src/app/not-found.tsx`); o que se perde é indexação em buscador.

E aqui há um fato que muda a avaliação: **até 25/09/2026 essas páginas estavam
inalcançáveis** — `/proposicoes/123/` devolvia a casca do 404, pelo mesmo
defeito de roteamento que quebrava `/sobre/`. Não existe tráfego de busca a
perder, porque nunca houve.

**Decidido em 25/09/2026: esperar uma semana antes de cortar.** Com o
roteamento corrigido e o top-10 do relatório filtrando só páginas servidas
(2xx/304), o relatório de **~02/10/2026** é a primeira medição honesta de se
alguém chega nessas páginas.

- Se `/proposicoes/` **não** aparecer no top-10: cortar o pré-render delas
  resolve o custo inteiro, sem perda medível.
- Se aparecer: discutir meio-termo — pré-renderizar só as mais acessadas, ou
  só as da legislatura corrente com tramitação recente.

Enquanto isso não acontece, não há pressa: o custo líquido é zero.

## Custo da funcionalidade de emendas (projeção, 25/09/2026)

A funcionalidade foi aprovada para construção. A projeção abaixo parte dos
números medidos na seção anterior, não de estimativa nova.

### O que ela acrescenta é barato

| item | PUT/mês | custo |
|---|---:|---:|
| páginas das 474 cidades com dado | +21 mil | **US$ 0,11** |
| ou: páginas dos 5.570 municípios | +252 mil | **US$ 1,26** |
| tabela `emenda` no RDS (~6,3 mil linhas/ano) | — | desprezível |
| ingestão diária da API da CGU (421 páginas) | — | ~3 min a mais de Fargate |

A API do Portal da Transparência é gratuita, com teto de 400 req/min. Nada
disso move o orçamento.

### O que não é óbvio, e é 35× maior

**O caro não é a fonte nova — é o rebuild que ela dispara.**

O deploy do frontend reconstrói quando o watermark de `/meta/status` muda, e
hoje isso acontece **1,51 vez por dia** com quatro fontes. Se a ingestão de
emendas concluir num horário próprio, ela vira uma quinta mudança de
watermark e provoca **mais uma reconstrução completa dos 294.701 objetos**:

```
+294.701 objetos × 30 dias  =  +8,8 milhões de PUT/mês  =  +US$ 44,21/mês
```

Contra **US$ 1,26** das páginas da própria funcionalidade. A fonte nova custa
centavos; o rebuild que ela dispara custa 35 vezes mais que ela.

### Duas decisões de implementação que decorrem disso

**1. A ingestão de emendas roda dentro da janela das fontes existentes**
(02:00–07:30), não em horário próprio. O watermark muda uma vez, não duas.
Emenda não é dado de minuto — atraso de algumas horas é irrelevante para o
leitor e vale US$ 44/mês.

**2. Pré-renderizar só as 474 cidades com dado**, não os 5.570 municípios.
As outras 5.096 páginas seriam idênticas — a mesma tela de "nenhuma emenda
identificada" — e chegam pelo fallback de cliente, que passou a funcionar
com a correção de roteamento de 25/09/2026. Economiza 90% das páginas novas
sem perder nada: página vazia não tem o que indexar.

### E reforça a decisão de ~02/10

Esta projeção torna o número da seção anterior mais concreto: enquanto cada
publicação escrever 294.701 objetos, **qualquer** fonte ou página nova herda
esse custo multiplicado. Cortar o pré-render de proposições e votações não
economiza só os US$ 67/mês de hoje — barateia tudo que vier depois.

## Resumo

| | Total (45 dias) |
|---|---:|
| Estimado | US$ 236 (~R$ 1.298) |

Comparação lado a lado com GCP: ver
[CUSTOS_INFRA_GCP.md § Comparativo rápido](CUSTOS_INFRA_GCP.md#comparativo-rápido-com-o-plano-aws).
