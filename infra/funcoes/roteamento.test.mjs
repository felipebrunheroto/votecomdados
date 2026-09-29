// Teste da CloudFront Function de roteamento. `node roteamento.test.mjs`.
//
// Existe porque esta funcao decide, na borda, o que o site inteiro serve — e
// em 29/09/2026 ela passou a devolver 404 real para caminho desconhecido.
// Essa mudanca tem um modo de falha caro e silencioso: uma rota nova no
// `web/src/app` que ninguem acrescente em `ROTAS` vira 404 para todo mundo,
// e o build continua verde porque o Next gerou o HTML normalmente. As
// asercoes abaixo usam URLs medidas em producao, nao inventadas.
//
// Sem framework de proposito: `infra/` nao tem runner de teste, e um
// require/eval de 3 linhas roda em qualquer lugar que tenha node.

import { readFileSync } from "node:fs";
import { strict as assert } from "node:assert";

const fonte = readFileSync(new URL("./roteamento.js", import.meta.url), "utf8");
// A funcao nao pode ter `export` — a CloudFront exige `function handler` solto
// no escopo do arquivo. Entao o teste a carrega sem alterar o arquivo.
const handler = new Function(`${fonte}\nreturn handler;`)();

const chamar = (uri) => handler({ request: { uri } });

let falhas = 0;
function caso(nome, fn) {
  try {
    fn();
    console.log(`  ok   ${nome}`);
  } catch (e) {
    falhas += 1;
    console.log(`  FALHA ${nome}\n       ${e.message}`);
  }
}

const reescreve = (uri, destino) =>
  caso(`${uri} -> ${destino}`, () => assert.equal(chamar(uri).uri, destino));

const passaDireto = (uri) =>
  caso(`${uri} vai ao S3 sem reescrita`, () => assert.equal(chamar(uri).uri, uri));

const negaComPagina = (uri) =>
  caso(`${uri} = 404 com pagina`, () => {
    const r = chamar(uri);
    assert.equal(r.statusCode, 404);
    assert.match(r.headers["content-type"].value, /text\/html/);
  });

const negaComTexto = (uri) =>
  caso(`${uri} = 404 em texto`, () => {
    const r = chamar(uri);
    assert.equal(r.statusCode, 404);
    assert.match(r.headers["content-type"].value, /text\/plain/);
  });

// ---- as rotas que o site publica -------------------------------------------
// Uma quebra aqui e o site fora do ar, nao um detalhe de estilo.
console.log("\nrotas publicadas:");
reescreve("/", "/index.html");
reescreve("/sobre/", "/sobre/index.html");
reescreve("/sobre", "/sobre/index.html"); // sem barra: 2 acessos na semana de 22/09
reescreve("/dados-abertos/", "/dados-abertos/index.html");
reescreve("/municipios/SP/SANTOS/", "/municipios/SP/SANTOS/index.html");
reescreve("/municipios/SP/SANTOS", "/municipios/SP/SANTOS/index.html");
// Nome de municipio percent-encoded: TIETE com circunflexo. Uma classe de
// caractere ingenua ([A-Z]+) reprovaria 1.596 cidades pelas acentuadas.
reescreve("/municipios/SP/TIET%C3%8A/", "/municipios/SP/TIET%C3%8A/index.html");
reescreve(
  "/politicos/80aafc3a-4d0f-41c0-b5bd-ae451504a0c9/",
  "/politicos/80aafc3a-4d0f-41c0-b5bd-ae451504a0c9/index.html",
); // ACACIO FAVACHO, pre-renderizado
reescreve("/proposicoes/92867/", "/proposicoes/92867/index.html");
reescreve("/votacoes/28460/", "/votacoes/28460/index.html");

// ---- o fallback de cliente tem de sobreviver -------------------------------
// Estes NAO tem objeto no S3: a forma casa, o S3 da 403, o CloudFront
// reescreve para 200 + /404.html e o navegador resgata. Se a borda negasse
// aqui, ~27 mil candidatos ficariam inacessiveis — que e exatamente o que o
// `response_code = 200` do edge.tf existe para evitar.
console.log("\nfallback de cliente (forma conhecida, sem pre-render):");
reescreve(
  "/politicos/c9c1e5d4-a845-4ce5-b83f-483d1b936880/",
  "/politicos/c9c1e5d4-a845-4ce5-b83f-483d1b936880/index.html",
); // AARON SALLES TORRES, sem atuacao legislativa
reescreve("/proposicoes/99999999/", "/proposicoes/99999999/index.html");

// ---- arquivos ---------------------------------------------------------------
console.log("\narquivos (a borda nao adivinha se existem):");
passaDireto("/_next/static/chunks/19mx3mg6lkumu.js");
passaDireto("/_next/static/chunks/0eu529hp7340t.css");
passaDireto("/municipios.json");
passaDireto("/404.html");
passaDireto("/icon.svg");
passaDireto("/sobre/index.txt"); // payload RSC da navegacao de cliente
passaDireto("/.well-known/security.txt"); // comeca com ponto e NAO e sonda

// ---- caminho desconhecido: 404 de verdade ----------------------------------
// Todos estes respondiam 200 em producao em 29/09/2026.
console.log("\ndesconhecido (media 200 antes de 29/09/2026):");
["/naoexiste/", "/login", "/admin", "/dashboard", "/api", "/config", "/info",
 "/env", "/credentials", "/Dockerfile", "/municipios/", "/politicos/",
 "/proposicoes/", "/votacoes/", "/politicos/nao-e-uuid/", "/proposicoes/abc/",
 "/municipios/SP/", "/admin-panel/", "//", "/actuator/heapdump",
 // Ponto num segmento que NAO e o ultimo nao faz do caminho um arquivo: aqui
 // o ultimo segmento e "arquivo", entao isto e pedido de pagina — e nao ha
 // rota assim. A pagina /dados-abertos/ nao linka arquivo nenhum (conferido
 // em 29/09/2026: so /sobre e um link externo de licenca).
 "/dados-abertos/2026-09-25/arquivo"].forEach(negaComPagina);

// ---- sonda: 404 em texto puro ----------------------------------------------
console.log("\nsonda:");
["/.env", "/.env.local", "/.git/config", "/.aws/credentials", "/wp-login.php",
 "/config.php", "/phpinfo.php", "/web.config", "/application.properties",
 "/composer.lock", "/backup.zip", "/backup.tar.gz", "/client.ovpn",
 "/id_rsa.pub", "//xmlrpc.php"].forEach(negaComTexto);

console.log(falhas === 0 ? "\ntudo passou.\n" : `\n${falhas} falha(s).\n`);
process.exit(falhas === 0 ? 0 : 1);
