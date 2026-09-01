# Plano — dois bugs de `generateStaticParams` achados navegando

Origem: erros colados pelo usuário navegando no `docker compose up` recém
publicado. Investigando o primeiro, achei um segundo, de família diferente e
mais grave. Nenhum dos dois apareceu antes porque **ninguém tinha rodado
`next dev` contra um backend real** — com fixtures, o ramo HTTP de
`cliente.ts` nunca executa.

| | Bug | Alcance | Causa |
|---|---|---|---|
| **B1** | `/proposicoes/[id]` e `/votacoes/[id]` quebram para **qualquer** id | 100% dessas duas rotas | `GET /proposicoes` e `GET /votacoes` não existem na API |
| **B2** | `/politicos/[id]` quebra para candidatos sem atuação legislativa | Maioria dos ~28 mil candidatos (a minoria com atuação é a exceção) | `output: "export"` proíbe rota dinâmica fora de `generateStaticParams`, e o Next 16 aplica isso também em `next dev` |

**Ordem: B1 antes de B2.** B1 é um endpoint faltando — pequeno, sem
ambiguidade, sem decisão de produto envolvida. B2 é uma lacuna arquitetural
que já estava **documentada como em aberto** desde antes desta sessão
(`docs/FRONTEND.md`, bloco "⚠️ Restrição do Next.js") — right agora ela só
ficou visível.

---

## B1 — `/proposicoes` e `/votacoes` não existem como listagem

### O problema, com evidência

`web/src/lib/api/cliente.ts`:

```ts
export async function listarIdsDeProposicoes(): Promise<number[]> {
  if (BASE) {
    const ids = await buscarHttp<{ data: { id: number }[] }>("/proposicoes?pageSize=1000");
    ...
```

Chamado por `generateStaticParams()` de `proposicoes/[id]/page.tsx` e
`votacoes/[id]/page.tsx`. Contra a API real:

```
$ curl http://localhost:8080/api/v1/votacoes?pageSize=1000
{"error":{"code":"INTERNAL_ERROR","message":"Erro interno ao processar a requisição."}}
```

Log do container `api`:

```
org.springframework.web.servlet.resource.NoResourceFoundException:
No static resource api/v1/votacoes for request '/api/v1/votacoes'.
```

A rota **nunca existiu**. `docs/API.md` só documenta `GET /votacoes/{id}` e
`GET /proposicoes/{id}` (item único) — nunca uma listagem. E o
`RestControllerAdvice` (`Erros`) converte a exceção de rota-não-encontrada em
500 genérico em vez de 404, o que escondeu o problema real por trás do mesmo
envelope de "erro interno" que qualquer outra falha produz.

**A peça que falta já existe, sem uso:**

```java
// VotacaoRepositorio.java
public List<Long> todosOsIds() {
    return jdbc.sql("SELECT id FROM votacao ORDER BY id").query(Long.class).list();
}
// ProposicaoRepositorio.java — o mesmo, para proposicao
```

Alguém já previu a necessidade do lado do banco. Ninguém ligou o fio até um
controller.

### O que fazer

1. **`ConsultaDetalhes`** ganha dois métodos, `todosOsIdsDeProposicoes()` e
   `todosOsIdsDeVotacoes()`, delegando para os `todosOsIds()` que já existem.
2. **`DetalheController`** ganha `GET /proposicoes` e `GET /votacoes`,
   devolvendo `{"ids": [...]}` — **sem paginação**, de propósito: a única
   finalidade deste endpoint é alimentar `generateStaticParams` no build; não
   é navegação, não deveria parecer uma feature de "listar todas as matérias"
   que o produto não decidiu oferecer. `pageSize=1000` no frontend também
   some — não existe mais página a pedir.
3. **`docs/API.md`** ganha uma seção curta para os dois, com a mesma nota:
   existe para geração estática, não para navegação.
4. **`cliente.ts`** ajusta `listarIdsDeProposicoes`/`listarIdsDeVotacoes` para
   o novo formato de resposta.
5. **Postman**: não precisa de request nova — não é rota pensada para
   exploração manual — mas vale um teste de integração no backend provando
   que a lista bate com `count(*)` da tabela.

### Como se prova

- Teste de integração: `GET /votacoes` devolve todos os ids da tabela, sem
  paginação, mesmo com mais de uma página de dados no seed.
- `docker compose up` de banco limpo → visitar `/votacoes/555111/` e
  `/proposicoes/1197773/` pela **UI real** (não só `curl` na API) → 200.
- `npm run build` (export estático) gera arquivo para toda votação e
  proposição do seed, sem exceção no build.

---

## B2 — candidato sem atuação legislativa não tem para onde ir

### O problema, com o que já estava escrito

Isto **não é descoberta nova** — `docs/FRONTEND.md` já tinha o bloco
"⚠️ Restrição do Next.js: não existe 'rota dinâmica não pré-gerada'", com as
três saídas possíveis e a frase final: *"Decidir antes do deploy. Até lá, o
desenvolvimento roda com fixtures pequenas, onde todos os perfis cabem no
`generateStaticParams`."* Essa premissa parou de valer no momento em que um
backend real entrou em cena — e confirmei que a mesma trava vale em
`next dev`, não só no build, contra a doc oficial do Next 16 (bundlada no
próprio `node_modules`):

> *"Attempting to use any of these features with `next dev` will result in
> an error"* — entre as features listadas: *"Dynamic Routes without
> `generateStaticParams()`"*.

`generateStaticParams()` de `politicos/[id]/page.tsx` só devolve quem tem
`possuiAtuacaoLegislativa = true` (via `listarIdsParaPreRender`, que filtra
por `comAtuacao: true`) — **por design**, documentado, correto: pré-renderizar
~28 mil candidatos a cada ingestão diária é inviável. O problema é o que
acontece com todos os outros.

### As três saídas já estavam escritas — nenhuma foi implementada

1. **Fallback no hospedador** (marcada "recomendado" no documento
   original): CloudFront/Firebase responde 200 com uma "casca renderizada no
   cliente" em vez de 404.
2. Pré-renderizar os ~28 mil.
3. Abandonar export estático por SSR/ISR.

Fui conferir se a metade que **não é infraestrutura** da opção 1 — a "casca
renderizada no cliente" em si, código de aplicação, sem depender de
CloudFront — existe. Não existe: `src/app/not-found.tsx` é uma mensagem
genérica de "página não encontrada", sem `usePathname()`, sem fetch de perfil,
sem nada que reconheça um `/politicos/{uuid}` e tente renderizá-lo no
navegador. A tabela de custos e a arquitetura em `docs/CUSTOS_INFRA_AWS.md` já
assumem export estático + CDN — não vou reabrir a opção 3 (SSR/ISR) neste
plano; é mudança de plano de custos, decisão de produto, fora do escopo de
"corrigir um erro de navegação".

### O que fazer — duas frentes, propósitos diferentes

**Frente A — desbloquear `next dev` agora (pequena, sem risco para produção)**

`output: "export"` só importa de verdade para `next build`. Torná-lo
condicional ao ambiente destrava o `next dev` local sem tocar no artefato que
vai pro ar — e sem precisar de nenhuma variável de ambiente nova: o próprio
Next já define `NODE_ENV` por comando (`development` no dev, `production` no
build), então basta amarrar a esse valor:

```ts
// next.config.ts
const nextConfig: NextConfig = {
  output: process.env.NODE_ENV === "production" ? "export" : undefined,
  ...
```

`next dev` passa a se comportar como um servidor Next normal: qualquer id
renderiza sob demanda, buscando dados via `obterPerfil` a cada requisição — o
que é exatamente navegar num app comum. `next build` não muda em nada, e vale
tanto para o `docker compose` quanto para `npm run dev` nativo, sem
configuração extra em nenhum dos dois.

**Frente B — construir a "casca" de verdade (o que falta pra opção 1 valer)**

1. Extrair o corpo de `PaginaPerfil` (tudo depois de obter `perfil`) para um
   componente compartilhado, ex. `PerfilDoPolitico({ perfil })` —
   reaproveitável tanto pela página SSG quanto pelo fallback client-side.
2. `not-found.tsx` vira componente cliente: lê o caminho atual
   (`usePathname()`), reconhece o padrão `/politicos/{uuid}/`, busca o perfil
   no cliente (`obterPerfil`, o mesmo client-side fetch que qualquer aba já
   usa) e renderiza `PerfilDoPolitico` com um estado de carregamento
   enquanto isso. Path que não bate com o padrão continua caindo na mensagem
   atual.
3. Isso resolve o "buraco" tanto para produção (uma vez que o CloudFront
   rewrite exista) quanto localmente: `next build && npx serve out` já serve
   `404.html` para ids fora do pré-render — falta só o `serve` (ou o
   CloudFront real) tratar 404 como 200. Registro no `docs/FRONTEND.md`
   deixando explícito que falta esse único passo de infraestrutura (grupo B do
   levantamento anterior — Dockerfile/IaC, nada disso está no repositório).

### O que este plano deliberadamente não inclui

- A configuração real do CloudFront (rewrite 404→200) — não existe IaC no
  repositório para nenhum recurso AWS ainda; entra junto quando essa fatia for
  aberta.
- Pré-renderizar mais candidatos, ou mudar o corte de `comAtuacao` — decisão
  de produto já tomada, não revisitada aqui.

### Como se prova

- `docker compose up` de banco limpo → clicar em **todo** candidato da
  listagem (`comAtuacao=true` e `=false`) → nenhum quebra o dev server.
- `npm run build` continua gerando só as páginas de quem tem atuação —
  confirmar contando arquivos em `out/politicos/`.
- Com a Frente B: `next build && npx serve out`, visitar um id sem atuação →
  a "casca" busca e renderiza o perfil no cliente (confirmável pela network
  tab: primeiro um 404 do arquivo estático, depois o fetch client-side bem
  sucedido).

---

## Ordem de execução recomendada

1. **B1** — endpoint faltando, sem ambiguidade, desbloqueia 100% de duas
   rotas inteiras.
2. **B2, Frente A** — libera a navegação local imediatamente (poucos
   minutos de trabalho).
3. **B2, Frente B** — a peça de aplicação que a arquitetura já previa e
   nunca foi construída; maior, vale revisão própria antes de entrar.

## Riscos

| Risco | Mitigação |
|---|---|
| Endpoint novo (B1) vira, sem querer, uma feature de "navegar todas as matérias" | Sem paginação, sem filtro, documentado explicitamente como uso interno de build |
| Frente A (B2) mascarar em dev um erro que só aparece no build de export real | `npm run build` continua obrigatório antes de qualquer deploy — Frente A não muda o comportamento do build, só do `next dev` |
| Frente B (B2) duplicar lógica entre a página SSG e a casca client-side | Componente único compartilhado (`PerfilDoPolitico`); a duplicação seria exatamente o que extrair evita |

---

## ✅ Entregue (01/09/2026)

Os dois bugs, corrigidos e verificados ponta a ponta contra o `docker compose`
da raiz do repositório:

- **B1**: `GET /proposicoes` e `GET /votacoes` implementados
  (`ConsultaDetalhes`, `DetalheController`), documentados em `docs/API.md`,
  com dois testes de integração novos (20/20 passando). `cliente.ts`
  atualizado para o novo formato `{"ids": [...]}`. Confirmado navegando
  `/votacoes/555111/` e `/proposicoes/1197773/` pela UI real (antes, 500).
- **B2, Frente A**: implementada de forma mais simples do que a esboçada
  acima — em vez de uma variável de ambiente de opt-in
  (`NEXT_DEV_SEM_EXPORT`), o `next.config.ts` final amarra `output: "export"`
  direto a `NODE_ENV === "production"`, que o próprio Next já define por
  comando. Motivo da mudança: investigando a Frente A descobri que o mesmo
  crash acontece também no `npm run dev` **nativo, sem Docker, só com
  fixtures** — ou seja, o bug nunca foi específico do Compose, e uma flag que
  só o `compose.yml` define não resolvia o caso nativo. Amarrar a `NODE_ENV`
  corrige os dois de uma vez, sem configuração extra em lugar nenhum.
- **B2, Frente B**: `PerfilDoPolitico` extraído como componente
  compartilhado; `not-found.tsx` reescrito como casca client-side que
  reconhece `/politicos/{uuid}/`, busca o perfil (`obterPerfil`) e renderiza
  o mesmo componente da página SSG, com estado de carregamento e mensagem de
  "não encontrado" para id inexistente. Um mismatch de hidratação (React
  error #418) apareceu na primeira versão — `usePathname()` decidia o ramo já
  na primeira renderização, divergindo do HTML gerado em build time; corrigido
  adiando a decisão para depois de montar (`useEffect`). Verificado com
  `next build && node scripts/servir-com-fallback.mjs` (servidor local novo
  que reproduz o rewrite 404→200 do hospedador, já que `npx serve -s`
  reescreve para `index.html`, não para `404.html`) via Playwright: perfil
  sem atuação renderiza completo, id inexistente mostra "candidato não
  encontrado", caminho inválido mostra a mensagem genérica — zero erros de
  console nos três casos.

**O que fica pendente, fora do escopo deste plano**: o rewrite 404→200 de
verdade no hospedador (CloudFront/Firebase) — infraestrutura, sem IaC no
repositório ainda.
