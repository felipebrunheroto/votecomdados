// Gera `out/sitemap.xml` a partir do que o build REALMENTE produziu.
// `node scripts/gerar-sitemap.mjs`, depois de `next build`.
//
// -- Por que nao e uma rota `app/sitemap.ts` ---------------------------------
//
// Era, ate 30/09/2026, e derrubou a publicacao duas vezes. A rota roda DENTRO
// do build e precisa perguntar a API quais paginas existem -- e o build ja
// estoura o WAF muito antes de chegar nela:
//
//     Error: API respondeu 403 em /emendas/municipios/dados
//
// Tentei resolver com retry. Nao funciona, e a razao importa: regra
// rate-based do WAF nao atrasa o excedente, ela BLOQUEIA todas as
// requisicoes do IP enquanto a contagem da janela estiver acima do limite.
// O build mantem a contagem acima do inicio ao fim, entao nao existe janela
// para a tentativa seguinte pegar.
//
// Depois do build nao ha essa disputa, porque nao ha chamada nenhuma: os
// diretorios de `out/` SAO as paginas publicadas. A concordancia entre
// sitemap e site deixa de ser algo a verificar e passa a ser construcao.
//
// -- Por que olhar o <title> -------------------------------------------------
//
// Pagina existir no disco nao significa ter conteudo. O build faz uma chamada
// a API por pagina, 60.598 delas, contra 600 por 5 minutos: o que passa do
// limite recebe 403, `obterPerfil` traduz erro em `null`, a pagina chama
// `notFound()` e vai para o disco como CASCA -- HTML valido, corpo vazio,
// titulo padrao do layout. Medido em 30/09/2026: ~350 dos 695 politicos tem
// conteudo, e as 58.276 paginas de proposicao e votacao nenhuma.
//
// Anunciar casca a um buscador e pedir que ele indexe "pagina nao
// encontrada". Entao entra no sitemap so quem tem titulo proprio, e o numero
// de excluidas vai para o log do deploy -- e a primeira medicao automatica de
// um problema que passou meses mudo.

import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { readdirSync } from "node:fs";
import { join, relative, sep } from "node:path";

const SAIDA = "out";
const SITE = "https://votecomdados.com.br";

// O titulo que o layout aplica quando a pagina nao define o seu. Casca tem
// exatamente este; pagina de verdade tem "<nome> · VoteComDados".
const TITULO_PADRAO = "VoteComDados — atuação dos candidatos de 2026";

// As rotas que o sitemap cobre. /proposicoes/* e /votacoes/* ficam de fora:
// 58.276 paginas cujo pre-render esta em decisao, que passariam o limite de
// 50.000 URLs por arquivo, e que hoje sao todas casca.
const RAIZES = ["politicos", "municipios"];
const ESTATICAS = ["/", "/sobre/", "/dados-abertos/"];

function titulo(arquivo) {
  const html = readFileSync(arquivo, "utf8");
  const m = html.match(/<title>([^<]*)<\/title>/);
  return m ? m[1].trim() : "";
}

function diretoriosComPagina(raiz) {
  const achados = [];
  const andar = (dir) => {
    let entradas;
    try {
      entradas = readdirSync(dir, { withFileTypes: true });
    } catch {
      return; // rota sem nenhuma pagina neste build (fixtures, por exemplo)
    }
    if (entradas.some((e) => e.isFile() && e.name === "index.html")) achados.push(dir);
    for (const e of entradas) if (e.isDirectory()) andar(join(dir, e.name));
  };
  andar(join(SAIDA, raiz));
  return achados;
}

// O disco guarda o nome decodificado (`DIAS D'ÁVILA`); a URL servida e
// percent-encoded por segmento (`DIAS%20D'%C3%81VILA`). Encoding do segmento,
// nunca do caminho inteiro, senao as barras virariam %2F.
function paraUrl(dir) {
  const partes = relative(SAIDA, dir).split(sep).map(encodeURIComponent);
  return `${SITE}/${partes.join("/")}/`;
}

if (!existsSync(SAIDA)) {
  console.error(`erro: ${SAIDA}/ nao existe. O build rodou?`);
  process.exit(1);
}

const urls = [];
let cascas = 0;
const porRaiz = {};

for (const rota of ESTATICAS) {
  const arquivo = rota === "/" ? join(SAIDA, "index.html") : join(SAIDA, rota, "index.html");
  // A home legitimamente USA o titulo padrao — e o titulo dela. Por isso as
  // estaticas entram pela lista, sem passar pelo teste de casca.
  if (existsSync(arquivo)) urls.push(`${SITE}${rota}`);
}

for (const raiz of RAIZES) {
  porRaiz[raiz] = 0;
  for (const dir of diretoriosComPagina(raiz)) {
    if (titulo(join(dir, "index.html")) === TITULO_PADRAO) {
      cascas += 1;
      continue;
    }
    urls.push(paraUrl(dir));
    porRaiz[raiz] += 1;
  }
}

if (urls.length === 0) {
  console.error("erro: nenhuma pagina com conteudo. O build produziu so casca?");
  process.exit(1);
}

const xml =
  '<?xml version="1.0" encoding="UTF-8"?>\n' +
  '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n' +
  urls.map((u) => `<url>\n<loc>${u.replace(/&/g, "&amp;")}</loc>\n</url>`).join("\n") +
  "\n</urlset>\n";

writeFileSync(join(SAIDA, "sitemap.xml"), xml);

const resumo = Object.entries(porRaiz).map(([r, n]) => `${n} ${r}`).join(", ");
console.log(`sitemap: ${urls.length} URLs (${ESTATICAS.length} estaticas, ${resumo})`);

if (cascas > 0) {
  // Aviso e nao erro: hoje sao milhares, e falhar aqui deixaria o site sem
  // poder publicar nada. O numero existe para nao voltar a ser invisivel.
  console.log(
    `::warning::${cascas} pagina(s) ficaram de fora do sitemap por serem casca ` +
    `(titulo padrao, corpo vazio) — o build nao conseguiu os dados na API. ` +
    `Ver o comentario no topo de scripts/gerar-sitemap.mjs.`,
  );
}

const robots = join(SAIDA, "robots.txt");
if (!existsSync(robots) || !readFileSync(robots, "utf8").includes(`${SITE}/sitemap.xml`)) {
  console.error("erro: robots.txt ausente ou sem apontar para o sitemap.");
  process.exit(1);
}
