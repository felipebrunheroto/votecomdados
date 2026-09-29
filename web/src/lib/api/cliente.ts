/**
 * Cliente da API interna.
 *
 * Enquanto o backend não existe, resolve tudo com as fixtures locais. A costura
 * é única e explícita: quando `NEXT_PUBLIC_API_URL` estiver definida, cada
 * função passa a bater no HTTP real. Nenhuma tela precisa mudar, porque todas
 * consomem apenas os tipos de `tipos.ts`, que são o contrato de docs/API.md.
 */
import {
  DETALHES_PROPOSICAO, EMENDAS_DO_POLITICO, EMENDAS_POR_MUNICIPIO,
  MUNICIPIOS_COM_EMENDA, PERFIS, PROPOSICOES, RESUMOS, STATUS_FONTES,
  TODAS_PROPOSICOES, VOTACOES, VOTACOES_DETALHE, paginar,
} from "./fixtures";
import type {
  EmendasDoMunicipio, FiltroPoliticos, Pagina, PaginaDeEmendas, PoliticoPerfil,
  PoliticoResumo, Proposicao, ProposicaoDetalhe, StatusFontes, VotacaoDetalhe,
  VotacaoDoPolitico,
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
/**
 * Emendas de autoria do parlamentar.
 *
 * Devolve `null` quando a API falha, e a seção some — ausência de emenda é o
 * caso de 19 em cada 20 candidatos, então uma aba vazia ou um erro visível
 * seriam ruído em quase todo perfil.
 */
export async function listarEmendasDoPolitico(
  id: string, page = 1, pageSize = 20,
): Promise<PaginaDeEmendas | null> {
  if (BASE) {
    try {
      return await buscarHttp<PaginaDeEmendas>(
        `/politicos/${id}/emendas?page=${page}&pageSize=${pageSize}`,
      );
    } catch {
      return null;
    }
  }
  return comAtraso(EMENDAS_DO_POLITICO[id] ?? null);
}

/**
 * Todas as cidades de uma vez, buscadas UMA vez por processo.
 *
 * <h2>Por que isto existe</h2>
 *
 * O build faz uma página por cidade. Com 474 cidades, uma chamada por página
 * passava; com 1.596 o runner estourou o limite do WAF -- 600 requisições por
 * IP a cada 5 minutos -- e passou a receber 403. O cliente traduzia o erro em
 * `null`, a página chamava `notFound()`, e 1.596 páginas foram publicadas
 * como "não encontrada" sem nada acusar. A plataforma bloqueou o próprio
 * build.
 *
 * A promessa fica no módulo: o Next gera em vários processos, então são
 * poucas chamadas -- uma por processo -- no lugar de mil e seiscentas.
 */
let dadosDeMunicipios: Promise<EmendasDoMunicipio[]> | null = null;

export function listarDadosDeMunicipios(): Promise<EmendasDoMunicipio[]> {
  if (!dadosDeMunicipios) {
    dadosDeMunicipios = BASE
      ? buscarHttp<EmendasDoMunicipio[]>("/emendas/municipios/dados")
      : Promise.resolve(Object.values(EMENDAS_POR_MUNICIPIO));
  }
  return dadosDeMunicipios;
}

/**
 * O que uma cidade recebeu.
 *
 * <h2>Campo novo da API chega OPCIONAL aqui</h2>
 *
 * O build do frontend chama a API de PRODUÇÃO, e um PR que toca backend e
 * frontend dispara os dois deploys em paralelo. O build costuma vencer a
 * corrida — aconteceu duas vezes em 28/09/2026 — e então a resposta vem da
 * versão anterior da API, sem os campos recém-adicionados.
 *
 * Na primeira vez o efeito foi benigno: `listarMunicipiosComEmenda` caiu na
 * fixture e gerou 1 cidade em vez de 474. Na segunda, `LacunaNacional`
 * assumiu que `nacional` existia e derrubou o build inteiro, 60.553 páginas,
 * com `Cannot read properties of undefined`.
 *
 * Por isso campo novo entra como opcional no tipo até o deploy seguinte: o
 * compilador cobra o tratamento, e publicação não morre por enriquecimento
 * ausente.
 *
 * A API responde 200 com `temRegistro: false` para cidade sem registro — que
 * é o caso de 9 em cada 10 municípios —, então `null` aqui significa falha de
 * rede, não ausência de dado. Confundir os dois faria a página dizer "erro"
 * onde o certo é "esta fonte não diz nada sobre ela".
 */
export async function obterEmendasDoMunicipio(
  uf: string, municipio: string,
): Promise<EmendasDoMunicipio | null> {
  if (BASE) {
    try {
      return await buscarHttp<EmendasDoMunicipio>(
        `/emendas/municipios/${encodeURIComponent(uf)}/${encodeURIComponent(municipio)}`,
      );
    } catch {
      return null;
    }
  }
  const chave = `${uf.toUpperCase()}/${municipio.toUpperCase()}`;
  // Sem fixture, devolve "sem registro" em vez de null: é o caso comum, e
  // null significaria falha de rede.
  const semRegistro = EMENDAS_POR_MUNICIPIO["SP/TIETE"];
  return comAtraso(EMENDAS_POR_MUNICIPIO[chave] ?? {
    ...semRegistro,
    municipio: municipio.toUpperCase(), uf: uf.toUpperCase(),
  });
}

/**
 * Cidades com ao menos uma emenda identificada — 474 das 5.570.
 *
 * Só alimenta `generateStaticParams`. Falha devolve lista vazia em vez de
 * estourar: um build que quebra porque a API piscou é pior que um build sem
 * as páginas de cidade, que o fallback de cliente cobre.
 */
export async function listarMunicipiosComEmenda(): Promise<
  { uf: string; municipio: string }[]
> {
  if (BASE) {
    try {
      return await buscarHttp<{ uf: string; municipio: string }[]>("/emendas/municipios");
    } catch {
      // Lista vazia AQUI seria fatal: `output: export` recusa build com
      // generateStaticParams vazio. As fixtures garantem ao menos uma rota, e
      // todas as cidades reais continuam chegando pelo fallback de cliente.
      return MUNICIPIOS_COM_EMENDA;
    }
  }
  return comAtraso(MUNICIPIOS_COM_EMENDA);
}

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
