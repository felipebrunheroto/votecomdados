// Gera endpoints.http a partir da colecao, para a extensao REST Client
// (humao.rest-client), que nao exige conta no Postman.
import { readFileSync, writeFileSync } from "node:fs";

const col = JSON.parse(readFileSync("VoteComDados.postman_collection.json", "utf8"));
const out = [];

out.push("# VoteComDados - todos os endpoints da API local.");
out.push("# Extensao: REST Client (humao.rest-client). Clique em 'Send Request'.");
out.push("# Suba a API antes:  cd backend && docker compose up");
out.push("");
for (const v of col.variable ?? []) {
  if (v.description) out.push(`# ${v.description}`);
  out.push(`@${v.key} = ${v.value}`);
}
out.push("");

const resolver = (u) => (typeof u === "string" ? u : u.raw);

const percorrer = (itens, prefixo = "") => {
  for (const it of itens) {
    if (it.item) {
      out.push(`### ---------- ${it.name} ----------`, "");
      percorrer(it.item, it.name);
      continue;
    }
    out.push(`### ${prefixo} / ${it.name}`);
    if (it.request.description) {
      for (const linha of it.request.description.split("\n")) out.push(`# ${linha}`);
    }
    out.push(`${it.request.method} ${resolver(it.request.url)}`);
    for (const h of it.request.header ?? []) out.push(`${h.key}: ${h.value}`);
    if (it.request.body?.raw) out.push("", it.request.body.raw);
    out.push("");
  }
};
percorrer(col.item);

writeFileSync("endpoints.http", out.join("\n"));
console.log(`endpoints.http gerado: ${out.length} linhas`);
