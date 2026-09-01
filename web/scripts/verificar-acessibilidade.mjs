/**
 * Auditoria WCAG 2.1 AA das telas principais.
 *
 *   npm run dev            # em outro terminal
 *   node scripts/verificar-acessibilidade.mjs
 *
 * Sai com código != 0 em qualquer violação, para uso direto no CI.
 * Acessibilidade é requisito declarado do projeto (docs/FRONTEND.md § 5), e
 * regressão de contraste é invisível em revisão de código.
 */
import { chromium } from "playwright";
import AxeBuilder from "@axe-core/playwright";

const navegador = await chromium.launch();
const pagina = await (await navegador.newContext()).newPage();

const rotas = [
  ["Home", "http://localhost:3000"],
  ["Perfil com atuação", "http://localhost:3000/politicos/a1000000-0000-4000-8000-000000000001"],
  ["Perfil sem atuação", "http://localhost:3000/politicos/a1000000-0000-4000-8000-000000000002"],
  ["Sobre os dados", "http://localhost:3000/sobre"],
  ["Dados abertos", "http://localhost:3000/dados-abertos"],
  ["Detalhe de proposição", "http://localhost:3000/proposicoes/1197773"],
  ["Votação nominal (placar)", "http://localhost:3000/votacoes/555111"],
  ["Votação simbólica", "http://localhost:3000/votacoes/555112"],
  ["Votação em comissão", "http://localhost:3000/votacoes/777001"],
];

let totalViolacoes = 0;

for (const [nome, url] of rotas) {
  await pagina.goto(url, { waitUntil: "networkidle" });
  await pagina.waitForTimeout(600);

  const r = await new AxeBuilder({ page: pagina })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
    .analyze();

  console.log(`\n${nome}: ${r.violations.length} violação(ões)`);
  for (const v of r.violations) {
    totalViolacoes += 1;
    console.log(`  [${v.impact}] ${v.id}: ${v.help}`);
    for (const n of v.nodes.slice(0, 2)) {
      console.log(`      ${n.html.slice(0, 110)}`);
    }
  }
}

// Também na aba de votações, que só existe após interação.
await pagina.goto("http://localhost:3000/politicos/a1000000-0000-4000-8000-000000000001", { waitUntil: "networkidle" });
await pagina.click('role=tab[name=/Votações/]');
await pagina.waitForTimeout(600);
const rv = await new AxeBuilder({ page: pagina })
  .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]).analyze();
console.log(`\nAba Votações: ${rv.violations.length} violação(ões)`);
for (const v of rv.violations) {
  totalViolacoes += 1;
  console.log(`  [${v.impact}] ${v.id}: ${v.help}`);
}

await navegador.close();
console.log(`\n=== TOTAL: ${totalViolacoes} violação(ões) WCAG 2.1 AA ===`);
process.exit(totalViolacoes === 0 ? 0 : 1);
