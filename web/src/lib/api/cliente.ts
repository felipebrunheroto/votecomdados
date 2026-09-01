/**
 * Cliente da API interna.
 *
 * Enquanto o backend não existe, resolve tudo com as fixtures locais. A costura
 * é única e explícita: quando `NEXT_PUBLIC_API_URL` estiver definida, cada
 * função passa a bater no HTTP real. Nenhuma tela precisa mudar, porque todas
 * consomem apenas os tipos de `tipos.ts`, que são o contrato de docs/API.md.
 */
import {
  DETALHES_PROPOSICAO, PERFIS, PROPOSICOES, RESUMOS, STATUS_FONTES,
  TODAS_PROPOSICOES, VOTACOES, VOTACOES_DETALHE, paginar,
} from "./fixtures";
import type {
  FiltroPoliticos, Pagina, PoliticoPerfil, PoliticoResumo, Proposicao,
  ProposicaoDetalhe, StatusFontes, VotacaoDetalhe, VotacaoDoPolitico,
} from "./tipos";

const BASE = process.env.NEXT_PUBLIC_API_URL;

/** Latência simulada: sem ela os estados de carregamento nunca aparecem em dev. */
const ATRASO_MS = 180;

function normalizar(texto: string): string {
  return texto.normalize("NFD").replace(/\p{Diacritic}/gu, "").toLowerCase();
}

/**
 * `BASE` decide SE o modo HTTP está ligado (é o interruptor que todas as
 * funções abaixo checam com `if (BASE)`); esta função decide PARA ONDE o
 * fetch realmente vai — e as duas podem divergir.
 *
 * O motivo é que `cliente.ts` roda em dois lugares que não enxergam a mesma
 * rede: no NAVEGADOR (toda função `"use client"`, incluindo as buscas
 * interativas) e no SERVIDOR (`generateStaticParams`, que roda dentro do
 * processo Next mesmo em `next dev`). Rodando os dois em containers Docker
 * separados — `web` e `api` — o navegador só alcança a API pela porta
 * publicada no host (`localhost:8080`), e o servidor, DENTRO do container
 * `web`, só a alcança pelo nome do serviço na rede do compose (`api:8080`);
 * `localhost` ali dentro é o próprio container `web`, não a API.
 *
 * `API_URL_INTERNO` (sem o prefixo `NEXT_PUBLIC_`, então nunca vai para o
 * bundle do navegador) é opcional: sem ela, o servidor usa a mesma URL do
 * navegador — o caso de fora do Docker, e o único que existe em produção,
 * onde o export estático não tem servidor nenhum rodando depois do build.
 */
function enderecoDeFetch(): string {
  if (typeof window === "undefined" && process.env.API_URL_INTERNO) {
    return process.env.API_URL_INTERNO;
  }
  return BASE!;
}

async function buscarHttp<T>(caminho: string): Promise<T> {
  const resposta = await fetch(`${enderecoDeFetch()}${caminho}`, {
    headers: { Accept: "application/json" },
  });
  if (!resposta.ok) {
    throw new Error(`API respondeu ${resposta.status} em ${caminho}`);
  }
  return (await resposta.json()) as T;
}

function comAtraso<T>(valor: T): Promise<T> {
  return new Promise((resolve) => setTimeout(() => resolve(valor), ATRASO_MS));
}

export async function listarPoliticos(
  filtro: FiltroPoliticos = {},
): Promise<Pagina<PoliticoResumo>> {
  const { q, cargo, uf, comAtuacao, page = 1, pageSize = 20 } = filtro;

  if (BASE) {
    const params = new URLSearchParams();
    if (q) params.set("q", q);
    if (cargo) params.set("cargo", cargo);
    if (uf) params.set("uf", uf);
    if (comAtuacao) params.set("comAtuacao", "true");
    params.set("page", String(page));
    params.set("pageSize", String(pageSize));
    return buscarHttp<Pagina<PoliticoResumo>>(`/politicos?${params}`);
  }

  let itens = RESUMOS;
  if (q) {
    const alvo = normalizar(q);
    itens = itens.filter(
      (p) =>
        normalizar(p.nomeCivil).includes(alvo) ||
        (p.nomeUrna ? normalizar(p.nomeUrna).includes(alvo) : false),
    );
  }
  if (cargo) itens = itens.filter((p) => p.cargo2026 === cargo);
  if (uf) itens = itens.filter((p) => p.uf === uf);
  if (comAtuacao) itens = itens.filter((p) => p.possuiAtuacaoLegislativa);

  return comAtraso(paginar(itens, page, pageSize));
}

export async function obterPerfil(id: string): Promise<PoliticoPerfil | null> {
  if (BASE) {
    try {
      return await buscarHttp<PoliticoPerfil>(`/politicos/${id}`);
    } catch {
      return null;
    }
  }
  return comAtraso(PERFIS.find((p) => p.id === id) ?? null);
}

export async function listarProposicoes(
  id: string, page = 1, pageSize = 20,
): Promise<Pagina<Proposicao>> {
  if (BASE) {
    return buscarHttp<Pagina<Proposicao>>(
      `/politicos/${id}/proposicoes?page=${page}&pageSize=${pageSize}`,
    );
  }
  return comAtraso(paginar(PROPOSICOES[id] ?? [], page, pageSize));
}

export async function listarVotacoes(
  id: string, page = 1, pageSize = 20,
): Promise<Pagina<VotacaoDoPolitico>> {
  if (BASE) {
    return buscarHttp<Pagina<VotacaoDoPolitico>>(
      `/politicos/${id}/votacoes?page=${page}&pageSize=${pageSize}`,
    );
  }
  return comAtraso(paginar(VOTACOES[id] ?? [], page, pageSize));
}

/**
 * Ids a pré-renderizar, paginando de verdade.
 *
 * A API impõe `pageSize` máximo de 100 (proteção contra varredura ampla, ver
 * ARQUITETURA.md § 10) e responde 400 acima disso. Pedir "tudo de uma vez"
 * quebrava o build inteiro contra a API real — este laço respeita o mesmo
 * limite que qualquer outro cliente.
 */
export async function listarIdsParaPreRender(): Promise<string[]> {
  const TAMANHO = 100;
  const ids: string[] = [];

  for (let page = 1; ; page += 1) {
    const pagina = await listarPoliticos({ comAtuacao: true, page, pageSize: TAMANHO });
    ids.push(...pagina.data.map((p) => p.id));

    const jaLidos = page * TAMANHO;
    if (pagina.data.length < TAMANHO || jaLidos >= pagina.pagination.total) break;
  }

  return ids;
}

export async function obterProposicao(id: number): Promise<ProposicaoDetalhe | null> {
  if (BASE) {
    try {
      return await buscarHttp<ProposicaoDetalhe>(`/proposicoes/${id}`);
    } catch {
      return null;
    }
  }
  const base = TODAS_PROPOSICOES.find((p) => p.id === id);
  if (!base) return comAtraso(null);
  return comAtraso({ ...base, autores: DETALHES_PROPOSICAO[id] ?? [] });
}

export async function obterVotacao(id: number): Promise<VotacaoDetalhe | null> {
  if (BASE) {
    try {
      return await buscarHttp<VotacaoDetalhe>(`/votacoes/${id}`);
    } catch {
      return null;
    }
  }
  return comAtraso(VOTACOES_DETALHE[id] ?? null);
}

/**
 * Frescor por fonte. Lido em TEMPO DE EXECUÇÃO de propósito: o HTML é estático
 * e pode ter sido gerado dias antes, então a data do build mentiria sobre a
 * atualidade dos dados (ver ARQUITETURA.md § 8).
 */
export async function obterStatusFontes(): Promise<StatusFontes> {
  if (BASE) return buscarHttp<StatusFontes>("/meta/status");
  return comAtraso(STATUS_FONTES);
}

export async function listarIdsDeProposicoes(): Promise<number[]> {
  if (BASE) {
    // `GET /proposicoes` não pagina: devolve TODOS os ids, sem filtro — a
    // única finalidade dela é alimentar generateStaticParams no build
    // (achado B1). Ver docs/API.md § GET /proposicoes.
    const r = await buscarHttp<{ ids: number[] }>("/proposicoes");
    return r.ids;
  }
  return TODAS_PROPOSICOES.map((p) => p.id);
}

export async function listarIdsDeVotacoes(): Promise<number[]> {
  if (BASE) {
    const r = await buscarHttp<{ ids: number[] }>("/votacoes");
    return r.ids;
  }
  return Object.keys(VOTACOES_DETALHE).map(Number);
}
