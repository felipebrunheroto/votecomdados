/**
 * Captura as telas principais, incluindo os casos de borda que revelam texto
 * ambíguo: perfil sem mandato, votação simbólica, voto de comissão, registro
 * indeferido e mandato anterior a 2001.
 *
 *   npm run dev            # em outro terminal
 *   node scripts/capturar-telas.mjs
 *
 * Saída em /tmp/vcd-shots.
 */
import { chromium } from "playwright";
import { mkdirSync } from "node:fs";

const SAIDA = "/tmp/vcd-shots";
mkdirSync(SAIDA, { recursive: true });

const navegador = await chromium.launch();
const erros = [];

async function capturar(pagina, nome, largura, altura) {
  await pagina.setViewportSize({ width: largura, height: altura });
  await pagina.waitForTimeout(500);
  await pagina.screenshot({ path: `${SAIDA}/${nome}.png`, fullPage: true });
  console.log(`  -> ${nome}.png`);
}

const ctx = await navegador.newContext();
const pagina = await ctx.newPage();
pagina.on("console", (m) => { if (m.type() === "error") erros.push(`[home] ${m.text()}`); });
pagina.on("pageerror", (e) => erros.push(`[pageerror] ${e.message}`));

console.log("HOME");
await pagina.goto("http://localhost:3000", { waitUntil: "networkidle" });
await pagina.waitForSelector("text=candidatos encontrados", { timeout: 10000 });
await capturar(pagina, "01-home-desktop", 1280, 900);
await capturar(pagina, "02-home-mobile", 390, 844);

console.log("BUSCA (filtro por texto)");
await pagina.setViewportSize({ width: 1280, height: 900 });
await pagina.fill("#busca-nome", "adriana");
await pagina.waitForTimeout(700);
await capturar(pagina, "03-busca-filtrada", 1280, 700);

console.log("PERFIL com atuacao");
await pagina.goto("http://localhost:3000/politicos/a1000000-0000-4000-8000-000000000001", { waitUntil: "networkidle" });
await pagina.waitForSelector("h1", { timeout: 10000 });
await capturar(pagina, "04-perfil-desktop", 1280, 1400);

console.log("PERFIL aba Votacoes");
await pagina.click('role=tab[name=/Votações/]');
await pagina.waitForTimeout(700);
await capturar(pagina, "05-perfil-votacoes", 1280, 1400);

console.log("PERFIL sem atuacao (dev server)");
await pagina.goto("http://localhost:3000/politicos/a1000000-0000-4000-8000-000000000002", { waitUntil: "networkidle" });
await pagina.waitForTimeout(500);
await capturar(pagina, "06-perfil-sem-atuacao", 1280, 1100);

console.log("PERFIL registro indeferido + mandato pre-2001");
await pagina.goto("http://localhost:3000/politicos/a1000000-0000-4000-8000-000000000003", { waitUntil: "networkidle" });
await pagina.click('role=tab[name=/Votações/]');
await pagina.waitForTimeout(700);
await capturar(pagina, "07-perfil-indeferido-votacoes-vazias", 1280, 1300);

console.log("MODO ESCURO");
const ctxEscuro = await navegador.newContext({ colorScheme: "dark" });
const paginaEscura = await ctxEscuro.newPage();
await paginaEscura.goto("http://localhost:3000/politicos/a1000000-0000-4000-8000-000000000001", { waitUntil: "networkidle" });
await paginaEscura.waitForTimeout(600);
await capturar(paginaEscura, "08-perfil-escuro", 1280, 1400);

await navegador.close();
console.log(erros.length ? `\nERROS DE CONSOLE:\n${erros.join("\n")}` : "\nNenhum erro de console.");
