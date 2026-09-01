/**
 * Valida a coleção antes de importar no Postman.
 *
 *   npm run validar                      # a coleção principal
 *   node validar-colecao.mjs outra.json  # qualquer arquivo
 *
 * Existe porque o Postman recusa a importação com uma mensagem genérica
 * ("Could not import collection. Please try again."), sem dizer o motivo.
 * São três verificações independentes, da mais formal para a mais prática:
 *
 *   1. schema oficial v2.1 (vendado em schema-v2.1.0.json, roda offline)
 *   2. carga pela postman-collection — a mesma lib que o Postman usa
 *   3. sanidade do conteúdo: UUID, URL resolvível, asserções presentes
 *
 * Se as três passam e a importação ainda falha, o problema está na extensão,
 * não no arquivo. O README explica como confirmar isso.
 */
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import Ajv from "ajv-draft-04";
import pkg from "postman-collection";

const { Collection } = pkg;
const aqui = dirname(fileURLToPath(import.meta.url));
const arquivo = process.argv[2] ?? join(aqui, "VoteComDados.postman_collection.json");

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const problemas = [];
const avisos = [];

// --- 0. o arquivo é JSON? -------------------------------------------------
let bruto;
try {
  bruto = JSON.parse(readFileSync(arquivo, "utf8"));
} catch (e) {
  console.error(`FALHOU — não é JSON válido: ${e.message}`);
  process.exit(1);
}

if (bruto._postman_variable_scope) {
  console.error(
    `${arquivo} é um arquivo de ambiente, não uma coleção. ` +
      "Valide a coleção: npm run validar",
  );
  process.exit(2);
}

// --- 1. schema oficial v2.1 ----------------------------------------------
const schema = JSON.parse(readFileSync(join(aqui, "schema-v2.1.0.json"), "utf8"));
const ajv = new Ajv({ strict: false, allErrors: true });
const valida = ajv.compile(schema);
if (!valida(bruto)) {
  for (const e of valida.errors.slice(0, 10)) {
    problemas.push(`schema: ${e.instancePath || "/"} ${e.message}`);
  }
}

// --- 2. carga pela lib do próprio Postman --------------------------------
let c;
try {
  c = new Collection(bruto);
} catch (e) {
  problemas.push(`postman-collection não carregou: ${e.message}`);
}

// --- 3. sanidade do conteúdo ---------------------------------------------
if (!UUID.test(bruto.info?._postman_id ?? "")) {
  problemas.push(`info._postman_id não é UUID válido: ${bruto.info?._postman_id}`);
}
if (!bruto.info?.schema?.includes("v2.1.0")) {
  problemas.push(`schema declarado não é v2.1.0: ${bruto.info?.schema}`);
}

let requisicoes = 0;
c?.forEachItem((item) => {
  requisicoes++;
  if (!item.request?.url?.toString()) problemas.push(`sem URL: ${item.name}`);
  if (item.events.count() === 0) avisos.push(`sem asserções: ${item.name}`);
});

// --- resultado ------------------------------------------------------------
if (problemas.length) {
  console.error(`FALHOU — ${problemas.length} problema(s) em ${arquivo}:`);
  problemas.forEach((p) => console.error(`  - ${p}`));
  process.exit(1);
}

avisos.forEach((a) => console.warn(`aviso: ${a}`));
console.log(
  `OK: schema v2.1 válido, carrega na lib do Postman, ${requisicoes} requisições com URL` +
    (avisos.length ? ` (${avisos.length} sem asserções).` : " e asserções."),
);
