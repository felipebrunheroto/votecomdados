# VoteComDados

Plataforma de transparência legislativa e eleitoral: para cada candidato da
eleição de **2026**, toda a vida política disponível — trajetória eleitoral nos
três níveis e atuação legislativa — cruzando dados do TSE, da Câmara dos
Deputados, do Senado Federal e da Alesp.

## Ver tudo rodando

```bash
docker compose up          # Postgres + API + Web, com dados de exemplo
```

Abra **http://localhost:3000**. Nenhuma instalação local de Java, Maven ou
Node é necessária — os três serviços rodam em container, com os fontes
bind-mountados (editar e salvar recarrega sozinho, como em dev nativo).
Detalhes, o que esperar em cada tela e como parar em
[backend/README.md § Ver tudo rodando](backend/README.md#ver-tudo-rodando).

**Escopo do MVP:** o sistema parte da lista de candidatos de 2026 (qualquer
cargo) e busca a vida política dessas pessoas. Quem não é candidato em 2026
não tem registro pessoal na base.

| Nível | Trajetória eleitoral | Autoria de matérias | Voto nominal em plenário |
|---|---|---|---|
| Federal — Câmara | ✅ TSE | ✅ 1934+ | ✅ só desde 2001 |
| Federal — Senado | ✅ TSE | ✅ 1991+ | ✅ desde 1991 (53% secretas) |
| Estadual — SP | ✅ TSE | ✅ Alesp | ❌ fonte não publica |
| Estadual — demais | ✅ TSE | ⏳ próxima versão | ⏳ próxima versão |
| Municipal | ✅ TSE | ⏳ próxima versão | ⏳ próxima versão |

A trajetória eleitoral cobre os três níveis desde já: vem dos mesmos arquivos
do TSE usados para montar a coorte, sem custo adicional.

A cobertura é assimétrica, e o schema distingue **"a fonte não publica"** de
**"ainda não cobrimos"** — são mensagens diferentes para o eleitor. Nem a
melhor assembleia estadual do país publica voto nominal de plenário; os 5.570
municípios não têm padrão comum, então a atuação legislativa municipal
provavelmente nunca será viável.

## Documentação

- [docs/ARQUITETURA.md](docs/ARQUITETURA.md) — **começar por aqui**:
  diagramas C4 (contexto, containers e componentes) em Mermaid, pipeline de
  ingestão, resolução de identidades, decisões de arquitetura e limitações.
- [db/schema.sql](db/schema.sql) — DDL PostgreSQL (requer PG 15+): schema
  curado (`Politico`, `Candidatura`, `Proposicao`, `Votacao`, `VotoNominal`),
  referência (`MapeamentoVoto`), controle de ingestão (`IngestaoExecucao`) e
  schema `staging` (payload redigido + quarentena).
- [db/test_invariantes.sql](db/test_invariantes.sql) — 50 testes executáveis
  das garantias do schema; cada um corresponde a um achado da revisão.
- [db/validar.sh](db/validar.sh) — aplica o schema num Postgres limpo e roda os
  invariantes duas vezes. `./db/validar.sh`
- [db/validar-migrations.sh](db/validar-migrations.sh) — prova que
  `db/schema.sql` e as migrations Flyway produzem o mesmo banco, estrutura e
  dados de referência. `./db/validar-migrations.sh`
- [db/golden/](db/golden/) — amostras verbatim das fontes oficiais, contra as
  quais o mapeamento de voto é testado. Foi inspecionando estes arquivos que se
  descobriu que a Câmara não publica ausência.
- O pacote de **dados abertos** (instantâneo datado, imutável, com metodologia e
  manifesto) é gerado pelo worker ao fim de cada ingestão — a plataforma publica
  de volta o dado que consolida, para que o cruzamento possa ser auditado de
  fora. O recorte publicável é o schema `dados_abertos`, e o invariante T48
  garante que nenhuma coluna pessoal escapa por ele.
- [tools/validar-contrato.mjs](tools/validar-contrato.mjs) — verifica que os
  enums do domínio coincidem entre banco, Java e TypeScript.
  `node tools/validar-contrato.mjs`
- [tools/validar-mermaid.mjs](tools/validar-mermaid.mjs) — valida os diagramas
  C4 da documentação. `cd tools && npm i && node validar-mermaid.mjs ../docs/*.md`

## Backend

A API fica em [backend/](backend/) — Java 21 + Spring Boot 4, multi-módulo
Maven. **Não é preciso instalar Java**: o build roda em container.

```bash
cd backend
docker compose up          # só Postgres + API em localhost:8080 (sem o frontend)
./mvnd clean test          # build e testes de integração (Testcontainers)
```

Detalhes e decisões em [backend/README.md](backend/README.md).

## Aplicação web

O frontend fica em [web/](web/) — Next.js 16 com export estático, Tailwind 4 e
TypeScript. Roda com fixtures locais enquanto a API não existe; a troca para o
backend real é uma variável de ambiente (`NEXT_PUBLIC_API_URL`).

```bash
cd web
npm install
npm run dev              # http://localhost:3000

npm run typecheck        # tsc --noEmit
npm run lint
npm run build            # export estático em web/out
npm run acessibilidade   # auditoria WCAG 2.1 AA (exige o dev server no ar)
npm run telas            # capturas dos casos de borda
```
- [docs/API.md](docs/API.md) — contrato REST consumido pelo frontend.
- [docs/CUSTOS_INFRA_AWS.md](docs/CUSTOS_INFRA_AWS.md) — plano financeiro
  de infraestrutura na AWS para os 45 dias de produção (sem piloto).
- [docs/CUSTOS_INFRA_GCP.md](docs/CUSTOS_INFRA_GCP.md) — mesmo plano
  equivalente na GCP, para comparação (provedor ainda não decidido).
- [docs/FRONTEND.md](docs/FRONTEND.md) — arquitetura de frontend (Next.js
  export estático, mapa de páginas, acessibilidade, SEO, deploy).
- [docs/BACKEND.md](docs/BACKEND.md) — arquitetura de backend (Java 21 +
  Spring Boot, jOOQ, virtual threads, módulos API e ingestão).
- [docs/PLANO_IMPLEMENTACAO.md](docs/PLANO_IMPLEMENTACAO.md) — plano dos três
  itens que a revisão deixou em aberto: spike do Senado, worker de ingestão e
  link para os dados abertos. **Começar a implementação por aqui.**
- [docs/PLANO_DEVSECOPS_IAC.md](docs/PLANO_DEVSECOPS_IAC.md) — plano para
  sair do `docker compose` local para nuvem de verdade: repositório git,
  CI/CD, segurança de supply chain e Terraform para os 45 dias de produção.
  Nada disso existe ainda hoje.
- [docs/REVISAO_ARQUITETURA.md](docs/REVISAO_ARQUITETURA.md) — revisão crítica
  da proposta antes da implementação, **encerrada em 31/08/2026**: 8 blockers
  corrigidos, 14 avisos tratados, 13 perguntas respondidas e 8 recomendações
  aplicadas. **Ler antes de começar a codificar** — vários achados invalidam
  decisões dos documentos acima, e a seção de encerramento lista o que
  continua aberto (o spike do Senado é o principal).
