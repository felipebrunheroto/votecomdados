/**
 * Formatação pt-BR.
 *
 * O fuso é fixado em America/Sao_Paulo de propósito: a API devolve UTC, e uma
 * votação às 21h de 15/06 em Brasília apareceria como 16/06 se formatada no
 * fuso do navegador de quem está fora do país — data errada numa plataforma
 * factual (ver docs/API.md § Convenções gerais).
 */
const FUSO = "America/Sao_Paulo";

const DATA = new Intl.DateTimeFormat("pt-BR", {
  day: "2-digit", month: "2-digit", year: "numeric", timeZone: FUSO,
});

const DATA_HORA = new Intl.DateTimeFormat("pt-BR", {
  day: "2-digit", month: "2-digit", year: "numeric",
  hour: "2-digit", minute: "2-digit", timeZone: FUSO,
});

const DATA_LONGA = new Intl.DateTimeFormat("pt-BR", {
  day: "numeric", month: "long", year: "numeric", timeZone: FUSO,
});

export function formatarData(iso: string | null): string {
  if (!iso) return "—";
  return DATA.format(new Date(iso));
}

export function formatarDataHora(iso: string): string {
  return DATA_HORA.format(new Date(iso));
}

export function formatarDataLonga(iso: string): string {
  return DATA_LONGA.format(new Date(iso));
}

const DATA_DO_DIA = new Intl.DateTimeFormat("pt-BR", {
  day: "numeric", month: "long", year: "numeric", timeZone: "UTC",
});

/**
 * Data de CALENDÁRIO ("2026-09-01"), sem hora — o oposto de
 * {@link formatarDataLonga}, e a diferença não é cosmética.
 *
 * `new Date("2026-09-01")` é meia-noite **UTC**. Formatada em
 * `America/Sao_Paulo` (UTC−3) ela vira 31 de agosto: um dia a menos, sempre.
 * É o mesmo erro de fuso que a plataforma trata na ingestão, na direção
 * contrária — e numa página que declara "este pacote é de tal dia", errar a
 * data é errar o endereço de citação.
 *
 * Aqui o valor já É um dia do calendário, sem instante associado, então
 * formatar em UTC é o que preserva o dia que a fonte declarou.
 */
export function formatarDataDoDia(iso: string): string {
  return DATA_DO_DIA.format(new Date(`${iso}T00:00:00Z`));
}

export function formatarNumero(n: number): string {
  return new Intl.NumberFormat("pt-BR").format(n);
}

const ROTULO_CARGO: Record<string, string> = {
  PRESIDENTE: "Presidente",
  VICE_PRESIDENTE: "Vice-presidente",
  GOVERNADOR: "Governador",
  VICE_GOVERNADOR: "Vice-governador",
  SENADOR: "Senador",
  PRIMEIRO_SUPLENTE: "1º suplente de senador",
  SEGUNDO_SUPLENTE: "2º suplente de senador",
  DEPUTADO_FEDERAL: "Deputado federal",
  DEPUTADO_ESTADUAL: "Deputado estadual",
  DEPUTADO_DISTRITAL: "Deputado distrital",
  PREFEITO: "Prefeito",
  VICE_PREFEITO: "Vice-prefeito",
  VEREADOR: "Vereador",
};

export function rotularCargo(cargo: string): string {
  return ROTULO_CARGO[cargo] ?? cargo;
}

const ROTULO_ESFERA: Record<string, string> = {
  FEDERAL: "Federal",
  ESTADUAL: "Estadual",
  MUNICIPAL: "Municipal",
};

export function rotularEsfera(esfera: string): string {
  return ROTULO_ESFERA[esfera] ?? esfera;
}

const ROTULO_STATUS: Record<string, string> = {
  DEFERIDO: "Registro deferido",
  INDEFERIDO: "Registro indeferido",
  CASSADO: "Registro cassado",
  RENUNCIA: "Renunciou",
  APTO: "Apto",
  INAPTO: "Inapto",
};

export function rotularStatusCandidatura(status: string): string {
  return ROTULO_STATUS[status] ?? status;
}

const ROTULO_CASA: Record<string, string> = {
  CAMARA: "Câmara dos Deputados",
  SENADO: "Senado Federal",
  ALESP: "Assembleia Legislativa de São Paulo",
};

export function rotularCasa(casa: string): string {
  return ROTULO_CASA[casa] ?? casa;
}
