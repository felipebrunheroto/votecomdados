// ---------------------------------------------------------------------------
// Valida a sintaxe de todos os blocos ```mermaid``` dos arquivos informados.
//
//   node tools/validar-mermaid.mjs docs/*.md
//
// Sai com código != 0 se algum bloco não fizer parse, o que o torna utilizável
// no CI. Os diagramas C4 são o principal entregável de docs/ARQUITETURA.md, e
// um bloco quebrado só apareceria como caixa de erro no GitHub.
//
// Requer `npm i mermaid jsdom` (ver tools/package.json).
// ---------------------------------------------------------------------------
import { readFileSync } from "node:fs";
import { JSDOM } from "jsdom";

const dom = new JSDOM("<!doctype html><html><body></body></html>", { pretendToBeVisual: true });
global.window = dom.window;
global.document = dom.window.document;
Object.defineProperty(global, "navigator", { value: dom.window.navigator, configurable: true });

const mermaid = (await import("mermaid")).default;
mermaid.initialize({ startOnLoad: false });

const arquivos = process.argv.slice(2);
if (arquivos.length === 0) {
  console.error("uso: node tools/validar-mermaid.mjs <arquivo.md> [...]");
  process.exit(2);
}

let totalBlocos = 0;
let falhas = 0;

for (const arquivo of arquivos) {
  const blocos = [...readFileSync(arquivo, "utf8").matchAll(/```mermaid\n([\s\S]*?)```/g)].map((m) => m[1]);
  if (blocos.length === 0) continue;

  console.log(`${arquivo}: ${blocos.length} diagrama(s)`);
  for (const [i, codigo] of blocos.entries()) {
    totalBlocos += 1;
    try {
      await mermaid.parse(codigo);
      console.log(`  [${i + 1}] OK`);
    } catch (erro) {
      falhas += 1;
      console.log(`  [${i + 1}] FALHOU: ${String(erro.message).split("\n")[0]}`);
    }
  }
}

console.log(falhas === 0
  ? `==> OK: ${totalBlocos} diagrama(s) validado(s)`
  : `==> FALHOU: ${falhas} de ${totalBlocos} diagrama(s) com erro`);
process.exit(falhas === 0 ? 0 : 1);
