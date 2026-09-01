/**
 * Serve `out/` simulando o rewrite 404 -> 200 que o CloudFront/Firebase
 * fariam em produção (ver `docs/PLANO_CORRECAO_STATIC_PARAMS.md`, B2).
 *
 * `npx serve -s` reescreve para `index.html` — a home, não a "casca" — então
 * não exercita `not-found.tsx`. Este servidor faz o rewrite certo: qualquer
 * caminho sem arquivo correspondente recebe `404.html` com status 200, que é
 * exatamente o que a página de perfil como fallback precisa para funcionar.
 *
 *   npm run build
 *   node scripts/servir-com-fallback.mjs
 *
 * Não é para produção — é só para verificar localmente, sem depender do
 * hospedador real, que a casca renderizada no cliente funciona.
 */
import { createServer } from "node:http";
import { readFile, stat } from "node:fs/promises";
import { extname, join, normalize } from "node:path";

const RAIZ = join(import.meta.dirname, "..", "out");
const PORTA = process.env.PORTA ?? 4000;

const TIPOS = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".ico": "image/x-icon",
  ".txt": "text/plain; charset=utf-8",
};

async function caminhoDoArquivo(urlPath) {
  const limpo = normalize(decodeURIComponent(urlPath)).replace(/^(\.\.[/\\])+/, "");
  const candidatos = limpo.endsWith("/")
    ? [join(RAIZ, limpo, "index.html")]
    : [join(RAIZ, limpo), join(RAIZ, limpo + ".html"), join(RAIZ, limpo, "index.html")];

  for (const caminho of candidatos) {
    try {
      const info = await stat(caminho);
      if (info.isFile()) return caminho;
    } catch { /* tenta o próximo */ }
  }
  return null;
}

createServer(async (req, res) => {
  const url = new URL(req.url, "http://localhost");
  const arquivo = await caminhoDoArquivo(url.pathname);

  if (arquivo) {
    res.writeHead(200, { "Content-Type": TIPOS[extname(arquivo)] ?? "application/octet-stream" });
    res.end(await readFile(arquivo));
    return;
  }

  // O rewrite: sem arquivo real, serve a casca — com 200, não 404, porque é
  // isso que faz o navegador tratar a resposta como página normal e rodar o
  // JS dela, em vez de mostrar o erro nativo do navegador.
  res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
  res.end(await readFile(join(RAIZ, "404.html")));
}).listen(PORTA, () => {
  console.log(`servindo out/ com fallback em http://localhost:${PORTA}`);
});
