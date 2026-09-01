/**
 * Captura as rotas de detalhe e a página de metodologia, em ambos os temas.
 * Saída em /tmp/vcd-shots2.
 */
import { chromium } from "playwright";
import { mkdirSync } from "node:fs";

const SAIDA = "/tmp/vcd-shots2";
mkdirSync(SAIDA, { recursive: true });
const nav = await chromium.launch();
const erros = [];

async function capturar(ctx, nome, url, larg, alt, espera) {
  const pg = await ctx.newPage();
  pg.on("console", (m) => { if (m.type() === "error") erros.push(`[${nome}] ${m.text()}`); });
  pg.on("pageerror", (e) => erros.push(`[${nome}] ${e.message}`));
  await pg.goto(url, { waitUntil: "networkidle" });
  if (espera) await pg.waitForSelector(espera, { timeout: 8000 });
  await pg.setViewportSize({ width: larg, height: alt });
  await pg.waitForTimeout(600);
  await pg.screenshot({ path: `${SAIDA}/${nome}.png`, fullPage: true });
  await pg.close();
  console.log(`  -> ${nome}`);
}

const claro = await nav.newContext();
await capturar(claro, "10-sobre", "http://localhost:3000/sobre", 1280, 1400, "text=Quando os dados foram atualizados");
await capturar(claro, "11-proposicao", "http://localhost:3000/proposicoes/1197773", 1280, 1000);
await capturar(claro, "12-votacao-nominal", "http://localhost:3000/votacoes/555111", 1280, 1100);
await capturar(claro, "13-votacao-simbolica", "http://localhost:3000/votacoes/555112", 1280, 900);
await capturar(claro, "14-votacao-comissao", "http://localhost:3000/votacoes/777001", 1280, 1100);
await capturar(claro, "15-votacao-mobile", "http://localhost:3000/votacoes/555111", 390, 844);

const escuro = await nav.newContext({ colorScheme: "dark" });
await capturar(escuro, "16-votacao-escuro", "http://localhost:3000/votacoes/555111", 1280, 1100);
await capturar(escuro, "17-sobre-escuro", "http://localhost:3000/sobre", 1280, 1400, "text=Quando os dados foram atualizados");

await nav.close();
console.log(erros.length ? `\nERROS:\n${erros.join("\n")}` : "\nNenhum erro de console.");
