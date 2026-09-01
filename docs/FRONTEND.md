# VoteComDados — Arquitetura de Frontend

Next.js (App Router, TypeScript) com **export estático**, hospedado em
S3 + CloudFront — mesma linha de custo já prevista em
[CUSTOS_INFRA_AWS.md](CUSTOS_INFRA_AWS.md), sem compute adicional. UI com
Tailwind CSS + shadcn/ui (componentes acessíveis por padrão, base Radix).

> A escolha de export estático (shell em SSG + abas dinâmicas via API) e
> tudo nas seções 2-8 vale independente de provedor. Só a seção 9 (deploy)
> usa nomenclatura AWS (S3, CloudFront) — se o provedor final for GCP, o
> equivalente é Firebase Hosting, conforme
> [CUSTOS_INFRA_GCP.md](CUSTOS_INFRA_GCP.md).

## 1. Estratégia de renderização

O export estático (`output: 'export'`) não roda servidor Node em produção
— o HTML é gerado no build e servido puro pela CDN. Isso implica separar o
que é **estável entre builds** do que é **sempre dinâmico**:

| Camada | Conteúdo | Como é servido |
|---|---|---|
| Shell estático (SSG) | Nome, partido, cargo, UF, trajetória | Gerado em build time via `generateStaticParams` — **apenas para candidatos com `possuiAtuacaoLegislativa = true`** |
| Conteúdo dinâmico (client-side) | Abas "Projetos apresentados" e "Votações", busca/filtro na home | `fetch` no client contra a API interna (`docs/API.md`), paginado, sempre fresco |
| Demais candidatos | Perfil de quem não tem atuação | **Exige fallback no hospedador** — ver o aviso abaixo |

> ### ⚠️ Restrição do Next.js: não existe "rota dinâmica não pré-gerada"
>
> Uma versão anterior deste documento previa pré-renderizar só quem tem
> atuação e servir os demais por rota dinâmica renderizada no cliente. **Isso
> não é possível com `output: "export"`.** A documentação do Next 16 lista
> como não suportados tanto "Dynamic Routes com `dynamicParams: true`" quanto
> "Dynamic Routes sem `generateStaticParams()`": no export estático só existem
> os caminhos devolvidos por `generateStaticParams`, e qualquer outro resulta
> em 404 sem HTML gerado.
>
> Verificado na prática: das 6 fixtures, o build gera 4 páginas — os 2
> candidatos sem atuação simplesmente não têm arquivo em `out/politicos/`.
>
> As saídas possíveis, em ordem de preferência:
>
> 1. **Fallback no hospedador** (recomendado): configurar CloudFront/Firebase
>    para responder 200 com uma casca renderizada no cliente em vez de 404.
>    Preserva URLs limpas e não muda o plano de custos. É o padrão de SPA
>    fallback, e o custo é que essas páginas não têm HTML pré-renderizado —
>    aceitável, já que perfis sem atuação têm pouco valor de SEO.
> 2. **Pré-renderizar todos os ~28 mil**: build de dezenas de minutos a cada
>    ingestão diária.
> 3. **Abandonar o export estático** e usar SSR/ISR: resolve tudo, mas exige
>    compute e altera os planos de custo.
>
> Decidir antes do deploy. Até lá, o desenvolvimento roda com fixtures
> pequenas, onde todos os perfis cabem no `generateStaticParams`.
>
> **Atualização (01/09/2026):** essa premissa — "até lá, fixtures pequenas
> bastam" — parou de valer assim que o backend real entrou em cena via
> `docker compose` (ver raiz do repositório). `npm run dev`/`next dev`
> **também** aplica esta restrição, não só `next build`: a doc oficial do
> Next 16 confirma — *"Attempting to use any of these features with `next
> dev` will result in an error"*. Contra o seed do perfil `dev` (6 pessoas
> nomeadas, 2 sem atuação), visitar `/politicos/.../000002` ou `/000006`
> quebra o dev server com `Error: ... is missing param ... required with
> "output: export" config`. É o mesmo problema desta seção, só que agora
> visível de verdade — o plano de correção está em
> [PLANO_CORRECAO_STATIC_PARAMS.md](PLANO_CORRECAO_STATIC_PARAMS.md).
>
> **Um segundo bug, de família diferente, apareceu investigando o primeiro:**
> `/proposicoes/[id]` e `/votacoes/[id]` têm o MESMO formato de
> `generateStaticParams`, mas quebram para **qualquer** id, não só uma
> minoria — `listarIdsDeProposicoes`/`listarIdsDeVotacoes` (`cliente.ts`)
> chamam `GET /proposicoes` e `GET /votacoes` (sem id), que **não existem**
> no contrato (`API.md` só documenta `/proposicoes/{id}` e `/votacoes/{id}`)
> nem no backend — a API responde 500 (`NoResourceFoundException`, verificado
> nos logs do container). O método de repositório que serviria a rota já
> existe dos dois lados (`VotacaoRepositorio.todosOsIds()`,
> `ProposicaoRepositorio.todosOsIds()`) — só nunca foi exposto por um
> controller. Também no plano de correção linkado acima.
>
> **✅ Entregue (01/09/2026)** — os dois bugs do plano acima, corrigidos:
>
> - **B1**: `GET /proposicoes` e `GET /votacoes` agora existem
>   (`{"ids": [...]}`, sem paginação, documentados em `API.md` como uso
>   interno de build). `cliente.ts` atualizado; dois testes de integração
>   novos provam que a lista bate com a tabela.
> - **B2**: duas frentes.
>   - *Frente A* — `next.config.ts` não usa mais variável de ambiente
>     nenhuma: `output: "export"` só entra quando `NODE_ENV === "production"`
>     (ou seja, só durante `next build` — é o próprio Next quem define essa
>     variável por comando). `next dev` nunca mais aplica a restrição de
>     export, com ou sem Docker — cobre também o `npm run dev` nativo deste
>     README, que tinha o mesmo problema e não fazia parte do relato
>     original.
>   - *Frente B* — a "casca renderizada no cliente" prevista acima existe:
>     `src/app/not-found.tsx` reconhece `/politicos/{uuid}/`, busca o perfil
>     no navegador (`obterPerfil`) e renderiza `PerfilDoPolitico` (extraído de
>     `politicos/[id]/page.tsx` para ser usado pelos dois caminhos). Verificado
>     com `next build && node scripts/servir-com-fallback.mjs` — um servidor
>     local que reproduz o rewrite 404→200 do CloudFront/Firebase, já que
>     `npx serve -s` reescreve para `index.html`, não para `404.html`, e não
>     exercitaria a casca. **Ainda falta** só a metade de infraestrutura: o
>     rewrite real no hospedador — não existe IaC no repositório para nenhum
>     recurso AWS ainda, entra junto quando essa fatia for aberta.

### Por que só parte é pré-renderizada

A coorte de 2026 tem ~28 mil candidatos, mas a **grande maioria nunca teve
mandato legislativo** — para eles não há proposição nem voto a mostrar. Gerar 28
mil páginas estáticas a cada ingestão diária significaria builds de dezenas de
minutos para produzir, quase todas, a mesma frase.

Então o SSG cobre só quem tem atuação (ordem de centenas a poucos milhares) e
o restante usa rota dinâmica. Nenhum candidato fica inacessível: a diferença é
apenas se o HTML vem pronto da borda ou é montado no navegador.

**Perfil sem atuação não é página de erro.** Precisa afirmar com clareza
"este candidato não exerceu mandato legislativo anterior" — resposta útil e
definitiva. Uma aba vazia sem texto deixa o usuário sem saber se o dado não
existe ou se a plataforma falhou, e é o mesmo defeito da votação simbólica.

Isso resolve o requisito de neutralidade/transparência (fonte sempre
visível e dado sempre atual nas abas) sem exigir um servidor SSR — o custo
de estar "sempre atual" fica só nas duas abas, que já eram paginadas via
API mesmo num cenário SSR.

### Pipeline de rebuild

O shell estático fica desatualizado (novo político, partido
mudou) até o próximo build.

**O gatilho é de puxar, não de empurrar** — o CI decide sozinho se precisa
reconstruir, em vez de esperar um webhook do worker (recomendação R3b):

```
cron do CI (de hora em hora)
        │
        ▼
GET /meta/status ──▶ watermark por fonte
        │
        ├─ igual ao de build-info.json publicado na CDN ──▶ não faz nada
        │
        └─ diferente
             ▼
        next build (lista de politico.id vem da API)
             ▼
        aws s3 sync ./out s3://bucket-frontend --delete \
            --exclude 'dados-abertos/20*'          ◀── protege os instantâneos
             ▼
        aws cloudfront create-invalidation --paths "/*"
             ▼
        publica build-info.json com o watermark que gerou este build
```

`build-info.json` é o estado do último build, e vive ao lado do site na própria
CDN — não há banco de controle de CI nem estado escondido no runner.

Por que assim, e não pelo webhook que o worker chamava ao concluir: **aquele
desenho falhava em silêncio** (A8). Se a chamada se perdesse — deploy do CI,
rede, token expirado — o site nunca era reconstruído e ninguém percebia; o dado
novo simplesmente ficava invisível. Aqui não existe mensagem para se perder: a
comparação é entre dois estados observáveis, e uma execução que falhe é
corrigida pela seguinte, sem intervenção. É o mesmo raciocínio do watermark da
ingestão — reconciliar é barato, perder evento é caro.

Custo: a comparação é um `GET` de dois arquivos pequenos, e o build só roda
quando há diferença — na prática uma vez por dia. Invalidação de CloudFront:
os primeiros 1.000 paths/mês são gratuitos, então uma invalidação diária de
`/*` fica bem dentro da faixa gratuita, sem impacto no custo já orçado.

### Link para os dados abertos ✅

O pacote é gerado e publicado (ver
[ARQUITETURA.md § 8b](ARQUITETURA.md#8b-dados-abertos-devolver-o-dado-consolidado)),
e agora **três telas apontam para ele** — antes, nenhuma. Dado publicado que
ninguém encontra não foi publicado de verdade.

| Onde | O quê |
|---|---|
| `Rodape` (novo, em todas as páginas) | Sobre os dados, `/dados-abertos` e download direto de `/dados-abertos/latest/` |
| `/dados-abertos` | A página: por que o pacote existe, licença CC BY 4.0, os cinco avisos do `LEIA-ME.md` em linguagem de leitor, os 12 arquivos, o que **não** está lá e como citar |
| Fim do perfil | Caminho de verificação junto do aviso de cobertura — é ali que a comparação injusta acontece |

Três decisões que não são de estilo:

1. **`<a>`, não `<Link>`, para o pacote.** `/dados-abertos/latest/` não é rota
   do Next: são arquivos que a ingestão publica na mesma CDN. Um `<Link>`
   tentaria navegação client-side para uma rota que o roteador não conhece.
2. **A volumetria é lida em tempo de execução**, do `manifesto.json` do próprio
   pacote (`ManifestoDoPacote`, mesmo padrão do `FrescorDosDados`). Número
   escrito à mão numa página cujo assunto é "confira o nosso trabalho"
   envelheceria em silêncio — e o build não sabe qual é o instantâneo mais
   recente. É essa leitura que permite oferecer o **endereço datado**, que é o
   de citação, em vez de mandar citar `latest`.
3. **A página publica os dois números desconfortáveis** — quantos votos são
   cálculo nosso e quantos vínculos vieram de semelhança de nome sem revisão
   humana. É o que separa dados abertos de peça de marketing, e já estava no
   `LEIA-ME.md`; esconder na tela o que o arquivo diz seria incoerente.

Sem o pacote publicado (o caso local), o componente mostra o estado de erro em
vez de números inventados: **não há fixture de manifesto**, de propósito. Uma
amostra sintética faria a página afirmar que existe um instantâneo publicado
quando não existe.

### Sem fotos de candidato

**Decisão de produto (01/09/2026): a plataforma não exibe foto de candidato.**
O cartão e o perfil mostram as iniciais do nome, e isso não é *fallback* — é a
única forma.

A decisão encerra de uma vez o problema do `next/image`, que **não funciona**
em export estático porque o otimizador exige servidor. Não há mais imagem de
pessoa a otimizar, redimensionar ou hospedar.

A coluna `foto_url` foi removida do schema e do contrato da API na V11, em vez
de ficar nula para sempre: campo que nunca será preenchido é promessa que não
se cumpre, e no contrato público ela seria cobrada em confiança por quem
integra.

## 2. Mapa de páginas

| Rota | Renderização | Situação | Descrição |
|---|---|---|---|
| `/` | SSG + client | ✅ | Busca/filtro por cargo, UF, nome |
| `/politicos/[id]` | SSG + client | ✅ | Perfil, trajetória, abas de Projetos/Votações, aviso de cobertura |
| `/proposicoes/[id]` | SSG | ✅ | Ementa, temas, autoria completa (coautores fora da coorte sem link) |
| `/votacoes/[id]` | SSG | ✅ | Placar, âmbito, matéria votada |
| `/sobre` | SSG + client | ✅ | Metodologia, cobertura, glossário de votos, frescor por fonte |
| `/dados-abertos` | SSG + client | ✅ | O pacote publicado: licença, os cinco avisos do `LEIA-ME.md`, volumetria lida do `manifesto.json` em tempo de execução |
| `not-found` (404) | SSG | ✅ | Fallback amigável |

> **Volumetria das rotas de detalhe.** `/proposicoes/[id]` e `/votacoes/[id]`
> sofrem da mesma restrição de export estático da página de perfil, e em escala
> pior: com dados reais são centenas de milhares de matérias, contra ~28 mil
> candidatos. Pré-renderizar todas é inviável, então essas duas rotas dependem
> do fallback no hospedador descrito no aviso acima — a decisão vale para as
> três rotas dinâmicas de uma vez.

Estado da busca/filtro (`q`, `cargo`, `uf`, `page`) vive na **URL**
(`useSearchParams`), não em estado local — resultado é compartilhável,
sobrevive a refresh e ao botão voltar do navegador.

## 3. Estrutura de pastas

```
app/
  layout.tsx                 # <html lang="pt-BR">, skip-link, providers
  page.tsx                   # home: busca + filtros
  politicos/[id]/
    page.tsx                 # perfil + abas
    loading.tsx / error.tsx
  proposicoes/[id]/page.tsx
  votacoes/[id]/page.tsx
  sobre/page.tsx
  not-found.tsx
components/
  ui/                        # primitivos shadcn (button, tabs, badge, skeleton...)
  domain/
    PoliticoCard.tsx
    PoliticoHeader.tsx
    ProposicaoList.tsx
    VotacaoList.tsx
    EtiquetaVoto.tsx          # enum -> label+cor+aria-label; SEMPRE com votoOrigem,
                              # origemRegistro e ambito
    TrajetoriaPolitica.tsx    # linha do tempo das disputas nos três níveis (TSE)
    AvisoDeCobertura.tsx      # o que cada esfera publica; obrigatório no perfil
    PlacarVotacao.tsx         # barra empilhada + tabela; paleta divergente validada
    FrescorDosDados.tsx       # GET /meta/status em runtime, não data de build
    FiltroBusca.tsx
    FonteOficialLink.tsx      # componente único para o link de fonte, reforça consistência
lib/
  api-client.ts               # wrapper fetch tipado sobre docs/API.md
  format.ts                   # Intl.DateTimeFormat/NumberFormat pt-BR
hooks/
  usePoliticos.ts              # React Query sobre GET /politicos
  useProposicoesDoPolitico.ts
  useVotacoesDoPolitico.ts
types/
  api.ts                       # tipos gerados/alinhados manualmente com API.md
```

## 4. Busca de dados

- **Build time:** script Node lê a API interna (`GET /politicos?comAtuacao=true`)
  para montar a lista de `politico.id` usada em `generateStaticParams` — só os
  candidatos com atuação legislativa registrada.
- **Client-side:** [TanStack Query](https://tanstack.com/query) sobre o
  `api-client.ts`, com paginação incremental nas abas de proposições e
  votações. O `staleTime` acompanha o TTL da borda (ver
  [API.md](API.md#cache-e-proteção-contra-abuso)) e evita refetch redundante
  ao trocar de aba e voltar — não há cache no backend para se alinhar.
- **Estados obrigatórios em toda lista paginada:** loading (skeleton),
  vazio ("nenhum resultado para esse filtro"), erro (mensagem + link para
  tentar de novo — nunca falha silenciosa, dado o requisito de
  transparência).

## 5. Acessibilidade (WCAG 2.1 AA)

- `<html lang="pt-BR">`; skip-link "Pular para o conteúdo" no topo do layout.
- Abas de perfil (Proposições/Votações) com `role="tablist"`/`tab`/`tabpanel`
  e navegação por seta do teclado (shadcn/ui `Tabs` já cobre isso).
- Foco visível e gerenciado: ao trocar de rota ou de aba, mover foco para
  o `h1`/heading da nova seção (leitores de tela não devem "perder" o
  usuário).
- Contraste mínimo AA em todos os tokens de cor (checar `EtiquetaVoto` em
  especial: SIM/NAO precisam ser diferenciáveis sem depender só de cor —
  usar ícone + texto, não só verde/vermelho).
- **`AvisoDeCobertura` é obrigatório no perfil**, não um rodapé discreto, e
  precisa de **três textos distintos** conforme o `status` da cobertura:
  *cobrimos* (`DISPONIVEL`), *a Casa não publica* (`NAO_PUBLICADO_PELA_FONTE`)
  e *ainda não cobrimos* (`FORA_DO_ESCOPO_MVP`). Um vereador de dez anos
  aparece com trajetória rica e nenhuma matéria; sem esse aviso o usuário lê
  "não fez nada" quando o correto é "não cobrimos câmaras municipais nesta
  versão". Comparar candidatos de níveis ou estados diferentes sem isso é
  comparar volume de publicação de dados, não atuação política.
- **A paleta do placar de votação foi validada por cálculo, não escolhida por
  gosto.** O par verde/vermelho convencional de painel parlamentar foi
  **reprovado**: sob deuteranopia dá ΔE 5,2, ou seja, quem tem daltonismo
  vermelho-verde não distingue "sim" de "não" — exatamente a informação que o
  placar existe para transmitir. O par azul/vermelho-alaranjado adotado dá
  ΔE 17,4 sob protanopia. Cada segmento tem rótulo direto e a tabela repete os
  números, então cor nunca é o único sinal.
- **`EtiquetaVoto` exibe o rótulo oficial (`votoOrigem`) junto do normalizado**, e
  a `notaMetodologica` quando presente, além de marcar visualmente quando o
  voto é de **comissão** e não de plenário. O enum é interpretação nossa; mostrar
  só ele atribuiria ao parlamentar uma conduta que pode não ter tido. É
  requisito de neutralidade, não detalhe de UI.
- **`AUSENTE` e `LICENCIADO` precisam de marcação própria**, porque não são
  registro da Casa: a fonte só lista quem votou, e as duas categorias saem do
  cruzamento com a lista de quem estava em exercício. Chegam com
  `origemRegistro: "DERIVADO"` e `votoOrigem: null` — a UI deve dizer que é
  cálculo da plataforma, e nunca exibir `AUSENTE` onde a pessoa estava
  licenciada. É a diferença entre "faltou" e "estava de licença", e ela não
  pode ficar por conta da interpretação do leitor.
- Fotos de candidato: `alt` com nome completo; placeholder com `alt=""`
  (decorativo) no lugar da foto, que a plataforma não exibe.
- Resultados de busca anunciados via `aria-live="polite"` (ex.: "42
  candidatos encontrados") para quem usa leitor de tela.
- Alvos de toque ≥ 44×44px (mobile-first); formulário de busca com
  `<label>` associado, nunca só `placeholder`.
- Respeitar `prefers-reduced-motion` em qualquer transição.
- Checagem automatizada: `@axe-core/react` em dev + `axe-playwright` no
  CI, bloqueando merge em violação nova.

## 6. Performance

- Shell estático servido 100% pela borda do CloudFront → TTFB bem abaixo
  dos 200ms alvo definidos em ARQUITETURA.md, já que não há compute no
  caminho crítico da primeira resposta.
- Abas dinâmicas: `Suspense` + skeleton evita layout shift; paginação
  (não scroll infinito) para manter previsibilidade e não sobrecarregar a
  API em buscas de político com centenas de votações.
- Fontes web: `next/font` com self-hosting (sem chamada externa a Google
  Fonts, reduz requests de terceiros e ajuda LCP).
- Bundle: `next/dynamic` para componentes pesados fora do caminho crítico
  (ex.: qualquer visualização futura de estatísticas).

## 7. SEO

- `generateMetadata` por rota (title, description, Open Graph sem foto do
  candidato) — perfis de político precisam ser indexáveis, é o principal
  caso de uso de busca orgânica da plataforma.
- `sitemap.xml` gerado em build time a partir da mesma lista de IDs usada
  no `generateStaticParams`.
- `robots.txt` liberando todas as rotas públicas.
- URLs canônicas estáveis (`/politicos/{uuid}`); considerar slug
  amigável (`/politicos/{uuid}-{nome-urna-slugificado}`) para
  legibilidade, mantendo o UUID como parte não ambígua da URL.

## 8. Testes

- **Unitário/componente:** Vitest + Testing Library (ex.: `EtiquetaVoto`
  mapeia corretamente cada enum de voto, `FiltroBusca` atualiza a URL).
- **Acessibilidade:** `axe-playwright` no fluxo E2E principal.
- **E2E (Playwright):** caminho de ouro completo — busca → filtro →
  perfil → aba de proposições → aba de votações → link de fonte oficial
  abre a URL correta.

## 9. Deploy (CI)

GitHub Actions, um workflow único disparado (a) em push para `main` e
(b) por cron de hora em hora, que só reconstrói se o watermark mudou (ver
"Pipeline de rebuild" acima — de propósito **não** é um trigger disparado
pelo worker ao concluir; esse desenho de push foi rejeitado, é o que a
tabela de decisões de arquitetura registra):

1. `npm run build` (Next.js export estático)
2. ```
   aws s3 sync ./out s3://<bucket-frontend> --delete \
       --exclude 'dados-abertos/20*'
   ```
3. `aws cloudfront create-invalidation --distribution-id <id> --paths "/*"`

> **O `--exclude` não é detalhe: sem ele o deploy apaga os dados abertos.**
>
> O site e o pacote dividem o prefixo `dados-abertos/` na mesma CDN — a
> página é exportada como `out/dados-abertos/index.html`, e a ingestão
> publica `dados-abertos/AAAA-MM-DD/*.csv` no mesmo bucket. Como os
> instantâneos não existem em `./out`, o `--delete` os removeria a cada
> publicação do site.
>
> Isso destruiria a promessa central do pacote: instantâneo datado é
> **endereço de citação imutável**, e um arquivo que some embaixo de quem o
> citou não serve de evidência. O padrão `dados-abertos/20*` casa com todo
> diretório datado e com nenhum arquivo gerado pelo Next — que começam com
> `index` ou `__next`.
>
> `latest/` é regravado pela própria ingestão a cada ciclo, então o
> `--delete` do site não pode alcançá-lo antes: publique-o **depois** do
> `sync`, ou acrescente `--exclude 'dados-abertos/latest/*'`.

Sem servidor novo, sem alteração no plano de custos já entregue — o
frontend inteiro cabe nas linhas de S3 + CloudFront já orçadas em
[CUSTOS_INFRA_AWS.md](CUSTOS_INFRA_AWS.md) (equivalente Firebase Hosting
em [CUSTOS_INFRA_GCP.md](CUSTOS_INFRA_GCP.md) se o provedor for GCP).
