#!/usr/bin/env node
/**
 * Verifica que os enums do domínio coincidem em TRÊS lugares:
 *
 *   db/schema.sql                          CREATE TYPE ... AS ENUM
 *   backend/.../core/dominio/Enums.java    public enum ...
 *   web/src/lib/api/tipos.ts               export type ... = "A" | "B"
 *
 * Existe porque essa divergência já aconteceu de verdade, e passou por todos
 * os testes: `LICENCIADO` entrou no banco e ficou faltando no Java e no
 * TypeScript. O build compilava, os testes passavam, e a falha só apareceria
 * em produção — no dia em que a primeira linha derivada chegasse à API.
 *
 * O valor do enum é o contrato: docs/API.md serializa o nome da constante.
 * Renomear ou esquecer um valor em qualquer um dos três quebra o frontend.
 *
 *   node tools/validar-contrato.mjs
 */
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const RAIZ = join(dirname(fileURLToPath(import.meta.url)), "..");
const ler = (p) => readFileSync(join(RAIZ, p), "utf8");

const sql = ler("db/schema.sql");
const java = ler("backend/votecomdados-core/src/main/java/br/org/votecomdados/core/dominio/Enums.java");
const ts = ler("web/src/lib/api/tipos.ts");

/**
 * CREATE TYPE nome AS ENUM ('A', 'B')
 *
 * Comentários `--` são removidos antes: eles citam valores entre aspas ao
 * explicar o enum, e sem a limpeza a explicação vira uma constante fantasma.
 */
function enumsDoBanco(texto) {
  const semComentarios = texto.replace(/--[^\n]*/g, "");
  const mapa = new Map();
  for (const m of semComentarios.matchAll(/CREATE TYPE (\w+) AS ENUM \(([\s\S]*?)\)\s*;/g)) {
    mapa.set(m[1], [...m[2].matchAll(/'([^']+)'/g)].map((v) => v[1]));
  }
  return mapa;
}

/**
 * public enum Nome { A, B }
 *
 * Comentários são removidos antes de separar por vírgula: um Javadoc entre
 * constantes contém vírgulas e pontuação, e sem a limpeza o parser reporta
 * divergência onde só há documentação.
 */
function enumsDoJava(texto) {
  const semComentarios = texto
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/\/\/[^\n]*/g, "");

  const mapa = new Map();
  const abertura = /public enum (\w+)\s*\{/g;
  let m;
  while ((m = abertura.exec(semComentarios)) !== null) {
    // Enum com corpo (métodos, campos) tem chaves aninhadas: parar na primeira
    // `}` pegaria o fim de um método, não o do enum. Aqui conta-se a
    // profundidade até fechar de verdade.
    let profundidade = 1;
    let i = abertura.lastIndex;
    while (i < semComentarios.length && profundidade > 0) {
      const c = semComentarios[i];
      if (c === "{") profundidade++;
      else if (c === "}") profundidade--;
      i++;
    }
    const corpo = semComentarios.slice(abertura.lastIndex, i - 1);
    // As constantes vêm antes do primeiro `;`; depois dele só há membros.
    const constantes = corpo.split(";")[0];
    mapa.set(m[1], constantes.split(",").map((v) => v.trim()).filter(Boolean));
    abertura.lastIndex = i;
  }
  return mapa;
}

/** export type Nome = "A" | "B"; — só uniões de literais, nunca objetos. */
function tiposDoTs(texto) {
  const semComentarios = texto
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/\/\/[^\n]*/g, "");
  const mapa = new Map();
  for (const m of semComentarios.matchAll(/export type (\w+)\s*=\s*([^;]+);/g)) {
    const corpo = m[2];
    if (!corpo.includes('"')) continue;
    if (/\w+\s*[<{]/.test(corpo)) continue; // genérico ou objeto: não é união
    mapa.set(m[1], [...corpo.matchAll(/"([^"]+)"/g)].map((v) => v[1]));
  }
  return mapa;
}

const banco = enumsDoBanco(sql);
const jvm = enumsDoJava(java);
const web = tiposDoTs(ts);

// [tipo no banco, enum Java, tipo TS (null = não chega ao frontend)]
const PARES = [
  ["cargo_enum", "Cargo", "Cargo"],
  ["esfera_enum", "Esfera", "Esfera"],
  ["casa_legislativa_enum", "CasaLegislativa", "CasaLegislativa"],
  ["ambito_votacao_enum", "AmbitoVotacao", "AmbitoVotacao"],
  ["tipo_voto_enum", "TipoVoto", "TipoVoto"],
  ["tipo_votacao_enum", "TipoVotacao", "TipoVotacao"],
  ["status_candidatura_enum", "StatusCandidatura", "StatusCandidatura"],
  ["status_cobertura_enum", "StatusCobertura", "StatusCobertura"],
  ["origem_registro_enum", "OrigemRegistro", "OrigemRegistro"],
  ["fonte_enum", "Fonte", null],
  ["status_execucao_enum", "StatusExecucao", null],
  ["tipo_job_enum", "TipoJob", null],
  ["motivo_rejeicao_enum", "MotivoRejeicao", null],
  ["metodo_resolucao_enum", "MetodoResolucao", null],
];

const problemas = [];
const mesmos = (a, b) => a.length === b.length && a.every((v, i) => v === b[i]);

for (const [nomeSql, nomeJava, nomeTs] of PARES) {
  const noBanco = banco.get(nomeSql);
  if (!noBanco) { problemas.push(`${nomeSql}: nao encontrado em db/schema.sql`); continue; }

  const noJava = jvm.get(nomeJava);
  if (!noJava) problemas.push(`${nomeJava}: nao encontrado em Enums.java`);
  else if (!mesmos(noBanco, noJava))
    problemas.push(`${nomeSql} != ${nomeJava}\n     sql : ${noBanco.join(", ")}\n     java: ${noJava.join(", ")}`);

  if (!nomeTs) continue;
  const noTs = web.get(nomeTs);
  if (!noTs) problemas.push(`${nomeTs}: nao encontrado em tipos.ts`);
  else if (!mesmos(noBanco, noTs))
    problemas.push(`${nomeSql} != ${nomeTs}\n     sql: ${noBanco.join(", ")}\n     ts : ${noTs.join(", ")}`);
}

/**
 * Tipos que vivem só no banco, cada um com o motivo. Ficam aqui de propósito:
 * a alternativa — ignorar em silêncio o que não está em PARES — deixaria um
 * enum novo passar sem ninguém decidir se ele pertence ao domínio.
 *
 * Todos são do worker de ingestão, que ainda não foi escrito. Quando for,
 * estes devem migrar para PARES junto com os enums Java correspondentes.
 */
const SO_NO_BANCO = new Map([
  ["situacao_exercicio_enum", "worker: situacao do parlamentar na Casa (deriva ausencia/licenca)"],
  ["condicao_eleitoral_enum", "worker: titular ou suplente, vindo do cadastro da Casa"],
]);

// Um enum novo no banco que ninguem classificou também é divergência — só que
// silenciosa, porque nada o procura.
const naoClassificados = [...banco.keys()].filter(
  (k) => !PARES.some(([s]) => s === k) && !SO_NO_BANCO.has(k));
if (naoClassificados.length) {
  problemas.push(
    `tipos do banco nao classificados: ${naoClassificados.join(", ")}\n` +
    `     acrescente a PARES (chega ao dominio) ou a SO_NO_BANCO (com o motivo)`);
}

// E o inverso: justificativa que sobrou depois de o tipo ser removido.
const justificativasOrfas = [...SO_NO_BANCO.keys()].filter((k) => !banco.has(k));
if (justificativasOrfas.length) {
  problemas.push(
    `SO_NO_BANCO cita tipos que nao existem mais: ${justificativasOrfas.join(", ")}`);
}

if (problemas.length) {
  console.error("FALHOU: contrato divergente entre banco, Java e TypeScript\n");
  for (const p of problemas) console.error(`  - ${p}`);
  process.exit(1);
}
console.log(`OK: ${PARES.length} enums coincidem entre banco, Java e TypeScript`);
console.log(`     ${SO_NO_BANCO.size} tipos ficam so no banco, com motivo declarado`);
