# VoteComDados — Plano Financeiro de Infraestrutura (GCP)

Equivalente em Google Cloud Platform ao plano em
[CUSTOS_INFRA_AWS.md](CUSTOS_INFRA_AWS.md), mesmo escopo: um único período de
**produção de 45 dias corridos**, sem piloto separado — o site sobe já em
escala de produção e permanece no ar só pelos 45 dias combinados.

**AWS foi o provedor decidido (01/09/2026)** — ver
[PLANO_DEVSECOPS_IAC.md § 3](PLANO_DEVSECOPS_IAC.md#3-decisões-do-owner).
Este documento deixou de ser implementado e passa a existir só como
referência de comparação, não como alternativa em aberto.

## Premissas

- **Região:** `southamerica-east1` (São Paulo) — mesma lógica que orientava
  a escolha original de `sa-east-1` no plano AWS, antes de a AWS migrar para
  `us-east-1` por decisão de custo sobre latência (02/09/2026, ver
  [CUSTOS_INFRA_AWS.md § Premissas](CUSTOS_INFRA_AWS.md#premissas)): menor
  latência para o usuário final brasileiro, ao custo de um tier de preço
  mais alto que `us-central1`. Este documento não foi atualizado com a
  mesma migração — permanece como estava, só como referência.
- **Stack:** tradução direta dos componentes de [ARQUITETURA.md](ARQUITETURA.md)
  e [BACKEND.md](BACKEND.md) para os equivalentes GCP:

  | Componente (AWS) | Equivalente GCP |
  |---|---|
  | ECS Fargate — API (service 24/7) | Cloud Run (service, `min-instances=1`, CPU sempre alocada) |
  | ECS Fargate — Worker (job sob demanda) | Cloud Run Jobs (billing só pelo tempo de execução) |
  | RDS PostgreSQL | Cloud SQL for PostgreSQL |
  | ALB | Domain mapping nativo do Cloud Run (sem LB dedicado — ver nota) |
  | S3 (fotos) | Cloud Storage |
  | CloudFront (frontend estático) | Firebase Hosting |
  | Route 53 | Cloud DNS |
  | Secrets Manager | Secret Manager |
  | CloudWatch | Cloud Logging + Cloud Monitoring |
  | EventBridge Scheduler | Cloud Scheduler |

- **Sem VPC/NAT dedicado:** Cloud Run e Cloud Run Jobs acessam a internet
  pública diretamente (sem custo de NAT Gateway) e falam com o Cloud SQL
  via Cloud SQL Auth Proxy/IAM sobre IP público autorizado — evita o custo
  e a complexidade de um Serverless VPC Access connector nesta escala.
- **Sem Load Balancer dedicado para a API:** o Cloud Run oferece HTTPS +
  mapeamento de domínio customizado nativamente, sem cobrança extra.
  Diferença real em relação à AWS, que precisa de um ALB para expor o ECS
  Service — GCP economiza essa linha, ao custo de não ter WAF/roteamento
  avançado nesta fase (mesma limitação seria resolvida depois com um
  External HTTPS Load Balancer + Cloud Armor, se necessário).
- **Sem broker de fila:** o worker é um job batch fatiado por `(fonte, ano)`,
  disparado direto pelo Cloud Scheduler — a arquitetura dispensa Pub/Sub
  (ver [ARQUITETURA.md § 5](ARQUITETURA.md#modelo-de-execução)).
- **Escopo pela coorte:** o banco guarda apenas as pessoas candidatas em 2026
  e o histórico delas, o que reduz o volume curado. Os CSVs anuais em massa
  são arquivados no object storage (imutáveis e reproduzíveis), não como
  JSONB no banco; o staging JSONB fica só para os payloads REST do
  incremental (ver [ARQUITETURA.md § 5](ARQUITETURA.md#a-coorte-de-2026-define-o-escopo)).
- **Direto para produção, sem fase piloto separada:** mesma decisão do plano
  AWS — a configuração abaixo já é a de tráfego público real desde o dia 1
  (2 instâncias de API com `min-instances=2`, Cloud Armor ativo, banco
  dimensionado para uso real). Não existe uma segunda fase "de uso" com
  sizing maior: os 45 dias são o período inteiro.
- **Tráfego do período:** mesmo cenário "pequeno / lançamento soft" usado no
  plano AWS, para manter os dois comparáveis.
- **Cloud Run tratado como "sempre ligado":** para comparar com o modelo
  Fargate (task 24/7), as instâncias de API abaixo assumem
  `min-instances` igual ao número de réplicas, rodando o tempo todo. Na
  prática, se o tráfego for irregular (baixo à noite/fim de semana), o
  Cloud Run pode escalar para menos instâncias nesses períodos e custar
  menos que o valor mostrado — o Fargate não tem essa elasticidade.
- **Valores em USD**, cotação de referência R$ 5,50 apenas para ordem de
  grandeza. Preços são **estimativas de planejamento** — validar na
  [Google Cloud Pricing Calculator](https://cloud.google.com/products/calculator)
  antes de comprometer orçamento.

---

## Os 45 dias

45 dias corridos ≈ 1,5 mês de 30 dias — os itens que cobram por
tempo-de-execução (compute, banco, tráfego) estão escalados por esse fator a
partir do preço mensal de referência. Dois itens **não** escalam com o
prazo:

- **Cloud Run Jobs — worker de ingestão histórica** (~25h): custo único,
  pago uma vez, antes do primeiro dia no ar — carrega o histórico completo
  (proposições desde 1934, votos desde 2001) antes de o site ter algo para
  mostrar.
- **Registro de domínio**: mesmo valor do plano AWS, independe do provedor
  de nuvem — registradores vendem em incrementos anuais, não em frações de
  45 dias.

| Item | Configuração | USD (45 dias) |
|---|---|---:|
| Cloud Run — API | 2 instâncias, 0,5 vCPU / 1 GiB cada, `min-instances=2` (24/7) | 138 |
| Cloud Run Jobs — Worker de ingestão histórica | 0,5 vCPU / 1 GiB, ~25h, único, antes do go-live | 2 |
| Cloud Run Jobs — Worker de ingestão incremental | 0,5 vCPU / 1 GiB, ~30h no período (cron diário) | 2 |
| Cloud SQL PostgreSQL | `db-g1-small`, single-zone, 30 GB SSD (só a coorte de 2026) | 63 |
| Cloud Armor | política + regra de rate limit (proteção da API pública) | 12 |
| Backup Cloud SQL | 7 dias de PITR + snapshot mensal retido | 2 |
| Cloud Storage (fotos + arquivo dos CSVs) | ~12 GB | 3 |
| Firebase Hosting | tráfego real (~75-150 GB no período) | 15 |
| Cloud DNS | | 2 |
| Secret Manager | 3 secrets — dentro do free tier | 0 |
| Cloud Logging + Monitoring | inclui métricas de negócio | 5 |
| Cloud Scheduler | cron de ingestão | 2 |
| Domínio (.org.br/.com.br via registro.br) | registro anual — único | 24 |
| **Subtotal** | | **≈ 270** |
| Margem de contingência (15%) | | 41 |
| **Total (45 dias)** | | **≈ US$ 311 (~R$ 1.711)** |

---

## Notas importantes

- **O crédito de conta nova quase paga o período inteiro.** Contas GCP novas
  recebem US$ 300 em créditos válidos por 90 dias. Um período de produção de
  45 dias cabe inteiro dentro dessa janela de validade, e o total estimado
  (US$ 311) fica a menos de 4% de distância do crédito — se a conta ainda
  não tiver sido usada para outro projeto, o custo real de sair do papel
  pode ser quase zero, restando só a margem de contingência não usada. Vale
  confirmar o saldo do crédito antes de assinar qualquer estimativa como
  "custo real".
- **Skipar o piloto perde parte da vantagem do Cloud Run em tráfego baixo.**
  Sem uma fase de tráfego pequeno separada, `min-instances=2` fica ligado
  desde o primeiro dia — é a mesma decisão feita no plano AWS (ir direto
  para o sizing de produção), só que aqui o efeito é mais visível: o Cloud
  Run só supera o Fargate em custo quando pode escalar para menos réplicas
  fora de pico, e esse plano deliberadamente não conta com isso (ver
  premissa "Cloud Run tratado como sempre ligado").
- **Secret Manager e Cloud Logging saem mais baratos que os equivalentes
  AWS** nesta escala, por causa do free tier mais generoso (6 versões de
  secret e 50 GB de log ingerido por mês, de graça). Diferença pequena em
  valor absoluto, mas consistente ao longo do período.
- **Impostos:** assim como na AWS, o valor final depende de como a conta
  é faturada — cobrança internacional em cartão sofre IOF (~5,38%);
  faturamento via entidade local (Google Cloud Brasil, BRL) tende a somar
  ISS/PIS-COFINS de forma semelhante ao caso AWS. Confirmar com o setor
  financeiro antes de fechar orçamento.
- **Cloud Armor é linha nova, exigida pela revisão de arquitetura.** A API é
  pública e anônima, e a busca trigram é barata de atacar e cara de servir.
  Rate limiting por IP antes do compute não é opcional, nem para um período
  curto.
- **O Memorystore foi removido, e é a maior economia do plano.** Com o cache
  HTTP de borda como camada primária ele já era candidato a corte; a definição
  de ~1.000 visitas/dia resolveu a dúvida antes da medição: espalhado por
  milhares de páginas, esse tráfego o deixaria tão frio quanto a borda, e um
  cache frio é só custo. Sai o equivalente a ~US$ 21/mês, um SPOF e uma
  dependência de runtime — mais do que o suficiente para pagar o Cloud Armor
  e a retenção de backup, que eram as duas mitigações que o orçamento
  original não comportava.
- **Backup é linha nova, decorrente da escolha por single-zone.** 7 dias de
  PITR mais um snapshot mensal retido: pouca coisa no tamanho previsto. O
  custo real não é dinheiro, é o **teste de restore**, que consome tempo de
  operação — e sem ele não há backup, há esperança. Vale rodar esse teste
  pelo menos uma vez dentro dos 45 dias.
- **Firebase Hosting precisa de uma rewrite — sem isso a maioria dos perfis
  de candidato fica inacessível em produção.** Mesma decisão de arquitetura
  do plano AWS (fallback no hospedador), já implementada no código do
  frontend (`web/src/app/not-found.tsx` reconhece `/politicos/{uuid}/`,
  busca o perfil no navegador e renderiza a página de verdade quando não há
  HTML pré-gerado — o caso da maioria dos ~28 mil candidatos, já que só quem
  tem atuação legislativa é pré-renderizado). A metade que falta é
  configuração: uma regra em `firebase.json` (`rewrites: [{ source: "**",
  destination: "/404.html" }]`) — mais simples que o equivalente no
  CloudFront, porque o Firebase Hosting já trata rewrite de SPA como caso
  nativo, sem precisar de Custom Error Response por código HTTP. **Sem custo
  adicional**, mas sem essa regra o app nunca chega a rodar para esses
  perfis. Ver
  [PLANO_CORRECAO_STATIC_PARAMS.md](PLANO_CORRECAO_STATIC_PARAMS.md) e
  [FRONTEND.md](FRONTEND.md) para o histórico da decisão; nenhum IaC para
  isso existe no repositório ainda.
- **Sem ambiente de staging:** mesma estratégia do plano AWS — rollout
  gradual via revisões do Cloud Run (`gcloud run services update-traffic`
  para canary/blue-green) em vez de um ambiente de teste isolado durante os
  45 dias. (Não confundir com o schema `staging` do banco, que é a camada
  de payload bruto.)
- **Encerramento ao final dos 45 dias.** Mesma consideração do plano AWS:
  desligar não é gratuito de decidir. Snapshot final do Cloud SQL antes de
  excluir a instância, e decisão sobre manter o Cloud Storage com os
  instantâneos de dados abertos (`dados-abertos/20*`) no ar depois de
  desligar Cloud Run/Cloud SQL — são citados como endereço permanente (ver
  [FRONTEND.md](FRONTEND.md)), e apagar o bucket quebraria essas citações.
  Decisão de produto, não coberta pelos números acima.
- **Se o prazo mudar, escala:** os mesmos gatilhos do plano AWS aplicam
  aqui — se surgir expectativa de pico de tráfego dentro do período, subir
  `db-g1-small` para um `db-custom` maior, adicionar réplica de leitura no
  Cloud SQL, e revisar `min-instances`/`max-instances` do Cloud Run antes do
  evento. É também o ponto em que reintroduzir um cache atrás da API
  voltaria a fazer sentido — medindo antes, não por precaução. Se o período
  se estender além de 45 dias, o custo recorrente escala linearmente a
  partir da tabela acima (item por item, exceto os dois marcados como
  únicos).

## Comparativo com o plano AWS (histórico — a decisão já foi tomada)

> A AWS foi decidida como provedor em 01/09/2026, e a região em 02/09/2026
> (`us-east-1`, custo sobre latência — ver
> [CUSTOS_INFRA_AWS.md § Premissas](CUSTOS_INFRA_AWS.md#premissas)). Este
> comparativo fica registrado como o raciocínio que embasou a escolha, não
> como uma decisão em aberto — ver
> [PLANO_DEVSECOPS_IAC.md § 3](PLANO_DEVSECOPS_IAC.md#3-decisões-do-owner).

| | AWS (região original, `sa-east-1`) | AWS (região decidida, `us-east-1`) | GCP (`southamerica-east1`) |
|---|---:|---:|---:|
| Total (45 dias) | US$ 269 (~R$ 1.480) | US$ 236 (~R$ 1.298) | US$ 311 (~R$ 1.711) |

**Leitura histórica:** no desenho original (as duas regiões de menor
latência ao Brasil), o GCP ficava ~16% mais caro que a AWS — o Cloud Run com
`min-instances` fixo cobra por vCPU/memória alocados o tempo todo, igual ao
Fargate, mas parte de uma base de preço por-instância mais alta para o mesmo
tamanho de tarefa. A migração da AWS para `us-east-1` (mais barata, mais
distante do usuário brasileiro) alarga essa diferença para ~32% — uma
comparação que já não é simétrica, já que o GCP mantém `southamerica-east1`
nesta tabela apenas como referência, sem uma migração equivalente cogitada.
Essa diferença de custo é **menor que o crédito de conta nova da GCP** (US$
300 em 90 dias), o que teria feito o GCP sair mais barato na prática para
quem abrisse conta pela primeira vez — um dado que pesou na decisão, mas não
foi o decisivo.

## Resumo

| | Total (45 dias) |
|---|---:|
| Estimado | US$ 311 (~R$ 1.711) |
