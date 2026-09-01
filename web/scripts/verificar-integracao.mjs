/**
 * Verifica o frontend consumindo a API real (não as fixtures).
 *
 *   backend: docker compose up      (porta 8080)
 *   web:     NEXT_PUBLIC_API_URL=... npm run dev
 *   node scripts/verificar-integracao.mjs
 */
import { chromium } from "playwright";
import { mkdirSync } from "node:fs";

const SAIDA = "/tmp/vcd-integracao";
mkdirSync(SAIDA, { recursive: true });

const nav = await chromium.launch();
const ctx = await nav.newContext();
const pg = await ctx.newPage();

const chamadasApi = [];
const erros = [];
pg.on("request", (r) => { if (r.url().includes(":8080/api/")) chamadasApi.push(r.url()); });
pg.on("console", (m) => { if (m.type() === "error") erros.push(m.text()); });
pg.on("pageerror", (e) => erros.push(e.message));

await pg.goto("http://localhost:3000", { waitUntil: "networkidle" });
await pg.waitForSelector("text=candidatos encontrados", { timeout: 15000 });
const total = await pg.textContent("text=candidatos encontrados");
console.log(`HOME: "${total?.trim()}"`);
await pg.screenshot({ path: `${SAIDA}/01-home-api-real.png`, fullPage: true });

// Clica no primeiro candidato para exercitar o perfil vindo da API.
await pg.click("a[href^='/politicos/']");
await pg.waitForSelector("h1", { timeout: 15000 });
const nome = await pg.textContent("h1");
console.log(`PERFIL: "${nome?.trim()}"`);
await pg.waitForTimeout(1200);
await pg.screenshot({ path: `${SAIDA}/02-perfil-api-real.png`, fullPage: true });

await pg.click('role=tab[name=/Votações/]');
await pg.waitForTimeout(1200);
await pg.screenshot({ path: `${SAIDA}/03-votacoes-api-real.png`, fullPage: true });

await nav.close();
console.log(`\nchamadas à API real: ${chamadasApi.length}`);
chamadasApi.slice(0, 6).forEach((u) => console.log(`  ${u.replace("http://localhost:8080", "")}`));
console.log(erros.length ? `\nERROS:\n${erros.join("\n")}` : "\nNenhum erro de console.");
process.exit(chamadasApi.length > 0 && erros.length === 0 ? 0 : 1);
