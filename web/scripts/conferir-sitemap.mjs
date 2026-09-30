// Confere que o sitemap e o export concordam. `node scripts/conferir-sitemap.mjs`
//
// Um sitemap tem exatamente uma forma de falhar sem ninguem notar: listar URL
// que nao existe, ou omitir URL que existe. Nenhuma das duas quebra o build,
// nenhuma aparece na tela, e a consequencia -- buscador rastreando 404, ou
// pagina real invisivel -- so se descobre semanas depois no Search Console.
//
// `sitemap.ts` reduz o risco lendo as MESMAS funcoes que alimentam os
// `generateStaticParams`. Isto aqui fecha: compara o XML gerado contra os
// diretorios que o export de fato criou, nos DOIS sentidos.
//
// Roda depois do build e antes da credencial de AWS, no deploy-frontend.

import { readFileSync, existsSync } from "node:fs";
import { readdir } from "node:fs/promises";
import { join, relative, sep } from "node:path";

const SAIDA = "out";
const SITE = "https://votecomdados.com.br";

// As rotas que o sitemap declara cobrir. Rota fora desta lista nao entra na
// conferencia inversa -- e o caso de /proposicoes/* e /votacoes/*, deixadas
// de fora de proposito enquanto o pre-render delas esta em decisao.
const COBERTAS = ["politicos", "municipios"];

if (!existsSync(join(SAIDA, "sitemap.xml"))) {
  console.error(`erro: ${SAIDA}/sitemap.xml nao existe. O build gerou o export?`);
  process.exit(1);
}

const xml = readFileSync(join(SAIDA, "sitemap.xml"), "utf8");
const urls = [...xml.matchAll(/<loc>(.*?)<\/loc>/g)].map((m) => m[1]);

if (urls.length === 0) {
  console.error("erro: sitemap sem nenhuma <loc>.");
  process.exit(1);
}

const ORIGEM = new URL(SITE).origin;

// O caminho no XML e percent-encoded; no disco, nao. DIAS D'ÁVILA chega como
// DIAS%20D'%C3%81VILA e tem de virar o diretorio com espaco e acento.
//
// Compara ORIGEM, nao prefixo de string. `url.startsWith(SITE)` tambem
// aceitaria https://votecomdados.com.br.outrodominio.com/ -- e o CodeQL
// aponta isso (js/incomplete-url-substring-sanitization). Aqui o insumo e o
// XML do nosso proprio build, entao nao havia caminho de exploracao; o motivo
// de trocar e que a versao com `new URL` afirma o que eu queria afirmar, e a
// com `slice` dependia de o prefixo ter exatamente o comprimento suposto.
const paraCaminho = (url) => {
  let parsed;
  try {
    parsed = new URL(url);
  } catch {
    throw new Error(`<loc> nao e URL valida: ${url}`);
  }
  if (parsed.origin !== ORIGEM) {
    throw new Error(`<loc> fora de ${ORIGEM}: ${url}`);
  }
  return decodeURIComponent(parsed.pathname);
};

const problemas = [];

// 1. Toda URL do sitemap tem arquivo?
//
// URL malformada entra como problema, nao como excecao: quem le a saida disto
// no CI quer a lista de problemas, nao um stack trace do node.
const listadas = new Set();
for (const url of urls) {
  let caminho;
  try {
    caminho = paraCaminho(url);
  } catch (e) {
    problemas.push(e.message);
    continue;
  }
  listadas.add(caminho);
  const arquivo = caminho === "/"
    ? join(SAIDA, "index.html")
    : join(SAIDA, caminho, "index.html");
  if (!existsSync(arquivo)) problemas.push(`no sitemap mas sem arquivo: ${url}`);
}

// 2. Toda pagina emitida das rotas cobertas esta no sitemap?
async function diretoriosComPagina(raiz) {
  const achados = [];
  async function andar(dir) {
    let entradas;
    try {
      entradas = await readdir(dir, { withFileTypes: true });
    } catch {
      return; // rota sem nenhuma pagina neste build (fixtures, por exemplo)
    }
    if (entradas.some((e) => e.isFile() && e.name === "index.html")) {
      achados.push(`/${relative(SAIDA, dir).split(sep).join("/")}/`);
    }
    for (const e of entradas) if (e.isDirectory()) await andar(join(dir, e.name));
  }
  await andar(join(SAIDA, raiz));
  return achados;
}

for (const raiz of COBERTAS) {
  for (const rota of await diretoriosComPagina(raiz)) {
    if (!listadas.has(rota)) problemas.push(`emitida mas fora do sitemap: ${rota}`);
  }
}

// 3. E o robots aponta para o sitemap?
const robots = existsSync(join(SAIDA, "robots.txt"))
  ? readFileSync(join(SAIDA, "robots.txt"), "utf8")
  : "";
if (!robots.includes(`${SITE}/sitemap.xml`)) {
  problemas.push("robots.txt nao aponta para o sitemap");
}

const cidades = urls.filter((u) => u.includes("/municipios/")).length;
const politicos = urls.filter((u) => u.includes("/politicos/")).length;
console.log(
  `sitemap: ${urls.length} URLs (${politicos} politicos, ${cidades} municipios, ` +
  `${urls.length - politicos - cidades} estaticas)`,
);

if (problemas.length > 0) {
  console.error(`\n${problemas.length} problema(s):`);
  for (const p of problemas.slice(0, 20)) console.error(`  ${p}`);
  if (problemas.length > 20) console.error(`  ... e mais ${problemas.length - 20}`);
  process.exit(1);
}
console.log("sitemap e export concordam, nos dois sentidos.");
