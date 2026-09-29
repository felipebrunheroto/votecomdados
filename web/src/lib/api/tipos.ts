/**
 * Tipos do contrato REST definido em docs/API.md.
 *
 * Enquanto a API não existe, o cliente serve fixtures com o mesmo formato
 * (ver `src/lib/api/cliente.ts`). Manter este arquivo fiel ao contrato é o
 * que garante que trocar fixture por backend real não mexa em nenhuma tela.
 */

export type Esfera = "FEDERAL" | "ESTADUAL" | "MUNICIPAL";

export type Cargo =
  | "PRESIDENTE" | "VICE_PRESIDENTE"
  | "GOVERNADOR" | "VICE_GOVERNADOR"
  | "SENADOR" | "PRIMEIRO_SUPLENTE" | "SEGUNDO_SUPLENTE"
  | "DEPUTADO_FEDERAL" | "DEPUTADO_ESTADUAL" | "DEPUTADO_DISTRITAL"
  | "PREFEITO" | "VICE_PREFEITO" | "VEREADOR";

/**
 * `NAO_INFORMADO` é o que a fonte diz, não falta de dado nosso: o TSE usa
 * sentinela enquanto o registro de candidatura está sendo julgado. A UI deve
 * dizer "situação ainda não informada pelo TSE", nunca "apto".
 */
export type StatusCandidatura =
  | "NAO_INFORMADO"
  | "DEFERIDO" | "INDEFERIDO" | "CASSADO" | "RENUNCIA" | "APTO" | "INAPTO";

/**
 * `null` apenas em votação simbólica, que não tem registro individual.
 *
 * Na **Câmara**, `AUSENTE` e `LICENCIADO` são DERIVADOS: a fonte lista só quem
 * registrou voto (cinco rótulos, nenhum de ausência), então os dois saem do
 * cruzamento com quem estava em exercício na data — chegam com
 * `origemRegistro: "DERIVADO"` e `votoOrigem: null`.
 *
 * No **Senado** é o oposto: a fonte publica a bancada inteira em cada votação,
 * com licença e ausência declaradas. Lá tudo é `FONTE`, e há três categorias
 * que só existem por causa dele:
 *
 * - `SECRETO` — votação secreta. A Casa registra que participou, não como
 *   votou. São 53% das votações de plenário do Senado.
 * - `PRESENTE_NAO_VOTOU` — estava na sessão e não registrou voto. Não é falta.
 * - `AUSENCIA_JUSTIFICADA` — ausência por missão oficial ou atividade
 *   parlamentar. Não é licença nem falta.
 *
 * Na **Alesp** só há voto de comissão, e ela publica um CÓDIGO de voto (8
 * valores documentados) além do texto livre. Dois deles não cabem em nenhuma
 * categoria anterior:
 *
 * - `VOTO_EM_SEPARADO` — votou apresentando parecer escrito divergente do
 *   relator. A fonte **não diz** se o divergente é favorável ou contrário ao
 *   projeto, e há registros dos dois tipos: inferir a direção seria inventar
 *   posição. Não é abstenção — abster-se é o oposto de divergir por escrito.
 * - `BRANCO` — voto em branco, que a Alesp conta separado da abstenção no
 *   placar dela.
 */
export type TipoVoto =
  | "SIM" | "NAO" | "ABSTENCAO" | "BRANCO"
  | "AUSENTE" | "LICENCIADO"
  | "AUSENCIA_JUSTIFICADA"
  | "PRESENTE_NAO_VOTOU"
  | "SECRETO"
  | "OBSTRUCAO" | "VOTO_EM_SEPARADO" | "ART_17";

/**
 * `FONTE`: a Casa publicou a linha. `DERIVADO`: a plataforma a calculou.
 *
 * A UI é obrigada a marcar a diferença — apresentar cálculo nosso como
 * registro oficial é o mesmo erro que `votoOrigem` existe para impedir.
 */
export type OrigemRegistro = "FONTE" | "DERIVADO";

export type TipoVotacao = "NOMINAL" | "SIMBOLICA";

/** Voto de comissão não tem o mesmo peso de deliberação em plenário. */
export type AmbitoVotacao = "PLENARIO" | "COMISSAO";

export type CasaLegislativa = "CAMARA" | "SENADO" | "ALESP";

/**
 * Em que recorte territorial a emenda foi registrada.
 *
 * Não é detalhe de catalogação: é a medida de quanto a fonte NÃO diz. Sobre
 * 2025 inteiro, por valor pago, MULTIPLO responde por 88,5% do dinheiro e
 * MUNICIPIO por 3,4%. Uma tela que some tudo sem separar por este campo
 * afirma uma cobertura que não tem.
 */
export type LocalidadeEmenda =
  | "MUNICIPIO"
  | "ESTADO"
  | "NACIONAL"
  | "MULTIPLO"
  | "OUTRO";

/**
 * As três situações de cobertura, que geram mensagens diferentes ao eleitor.
 * Confundir as duas últimas seria desonesto: "não existe" e "não fizemos"
 * são coisas distintas (ver docs/ARQUITETURA.md § 5).
 */
export type StatusCobertura =
  | "DISPONIVEL"
  | "NAO_PUBLICADO_PELA_FONTE"
  | "FORA_DO_ESCOPO_MVP";

export interface Cobertura {
  esfera: Esfera;
  uf: string | null;
  /**
   * `null` quando a regra não é de uma Casa (trajetória eleitoral, do TSE).
   *
   * Existe porque a esfera federal tem DUAS Casas com coberturas diferentes:
   * voto nominal da Câmara desde 2001, do Senado desde 1991. Quem passou pelas
   * duas recebe as duas linhas, e a UI precisa dizer de qual Casa cada uma é.
   */
  casa: CasaLegislativa | null;
  recurso: string;
  status: StatusCobertura;
  disponivelDesde: string | null;
  observacao: string;
}

export interface Candidatura {
  anoEleicao: number;
  cargo: Cargo;
  esfera: Esfera;
  uf: string;
  municipio: string | null;
  partidoSigla: string;
  status: StatusCandidatura;
  eleito: boolean | null;
}

export interface PoliticoResumo {
  id: string;
  nomeCivil: string;
  nomeUrna: string | null;
  cargo2026: Cargo;
  uf: string;
  partidoSigla: string;
  statusCandidatura: StatusCandidatura;
  possuiAtuacaoLegislativa: boolean;
}

export interface PoliticoPerfil {
  id: string;
  nomeCivil: string;
  nomeUrna: string | null;
  possuiAtuacaoLegislativa: boolean;
  /** Da disputa mais recente para a mais antiga. */
  trajetoria: Candidatura[];
  cobertura: Cobertura[];
}

export interface Proposicao {
  id: number;
  casa: CasaLegislativa;
  esfera: Esfera;
  siglaTipo: string;
  numero: number;
  /**
   * Nulo quando a peça não tem ano na designação oficial — parecer, emenda e
   * substitutivo são numerados dentro da tramitação de outra matéria.
   */
  ano: number | null;
  ementa: string;
  temas: string[];
  dataApresentacao: string | null;
  situacaoAtual: string | null;
  urlInteiroTeor: string | null;
  urlTramitacao: string;
}

export interface VotacaoDoPolitico {
  votacaoId: number;
  dataVotacao: string;
  descricao: string;
  casa: CasaLegislativa;
  esfera: Esfera;
  ambito: AmbitoVotacao;
  temas: string[];
  tipo: TipoVotacao;
  /**
   * Votação secreta é NOMINAL: a Casa registra quem participou, não como cada
   * um votou. A UI precisa dizer isso — caso contrário `SECRETO` seria lido
   * como recusa a votar.
   */
  secreta: boolean;
  /** `null` em votação simbólica; nesse caso `observacao` explica o motivo. */
  voto: TipoVoto | null;
  /** Rótulo literal da fonte. Obrigatório quando `origemRegistro` é `FONTE`. */
  votoOrigem: string | null;
  /**
   * De onde veio a linha. `DERIVADO` só ocorre em `AUSENTE` e `LICENCIADO`.
   * `null` em votação simbólica, onde não existe registro individual algum —
   * nem da fonte, nem derivado.
   */
  origemRegistro: OrigemRegistro | null;
  notaMetodologica?: string;
  observacao?: string;
  aprovada: boolean | null;
  urlFonte: string;
}

export interface Paginacao {
  page: number;
  pageSize: number;
  total: number;
}

export interface Pagina<T> {
  data: T[];
  pagination: Paginacao;
}

export interface FiltroPoliticos {
  q?: string;
  cargo?: Cargo;
  uf?: string;
  comAtuacao?: boolean;
  page?: number;
  pageSize?: number;
}

export interface ErroApi {
  error: { code: string; message: string };
}

/* ===========================================================================
   Detalhe de proposição e de votação, e frescor das fontes.
   =========================================================================== */

/**
 * Uma emenda parlamentar.
 *
 * `pago` e `restoPago` andam juntos, e `desembolso` é a soma. Não é
 * redundância: em 2025 saíram R$ 32,5 bi por `pago` e R$ 6,1 bi por restos a
 * pagar. Santos tem `pago = 0` e recebeu R$ 600 mil — **exibir `pago` como
 * número principal mente sobre 43 das 474 cidades com emenda identificada.**
 */
export interface Emenda {
  codigo: string;
  ano: number;
  tipo: string;
  autorNome: string;
  politicoId: string | null;
  /** Preenchido quando a emenda foi transferida de outro parlamentar. */
  autorOrigemNome: string | null;
  municipio: string | null;
  uf: string | null;
  localidadeBruta: string;
  localidadeTipo: LocalidadeEmenda;
  empenhado: number | null;
  liquidado: number | null;
  pago: number | null;
  restoPago: number | null;
  desembolso: number;
}

export interface FatiaDeLocalidade {
  localidade: LocalidadeEmenda;
  linhas: number;
  desembolso: number;
}

export interface PeriodoCoberto {
  anoInicio: number;
  anoFim: number;
}

export interface ResumoDeEmendas {
  linhas: number;
  empenhado: number;
  pago: number;
  restoPago: number;
  desembolso: number;
  porLocalidade: FatiaDeLocalidade[];
  /**
   * Anos que a base cobre — da NOSSA cobertura, não do recorte exibido.
   * Derivá-lo das linhas da tela diria "2025" para quem só teve emenda
   * naquele ano, sugerindo que é só disso que dispomos.
   */
  periodo: PeriodoCoberto | null;
  /**
   * Quantos municípios distintos têm ao menos uma emenda registrada.
   *
   * Vem da API, e não escrito aqui, porque já apodreceu três vezes: "474 de
   * 5.570" foi medido com um ano carregado e virou 1.596 quando quatro
   * entraram — a tela dizia "nove em cada dez municípios" quando já eram sete.
   */
  municipiosComRegistro?: number;
}

export interface PaginaDeEmendas {
  data: Emenda[];
  resumo: ResumoDeEmendas;
  pagination: Paginacao;
}

/**
 * O que uma cidade recebeu.
 *
 * `temRegistro` distingue os dois zeros, e a distinção não é sutil: 43
 * cidades receberam dinheiro e apareceriam como zero se só `pago` fosse
 * olhado, enquanto 40 outras realmente não receberam. Sem este campo,
 * "R$ 0,00" significaria as duas coisas.
 */
export interface EmendasDoMunicipio {
  municipio: string;
  uf: string;
  temRegistro: boolean;
  parlamentares: number;
  resumo: ResumoDeEmendas;
  /**
   * O acervo inteiro. O resumo do município não serve para declarar a
   * lacuna: ali todas as linhas são de município por construção, então a
   * fração local é sempre 100% e não diz nada sobre o que ficou de fora.
   */
  nacional?: ResumoDeEmendas | null;
  emendas: Emenda[];
}

export interface AutorProposicao {
  /** `null` para coautor que não é candidato em 2026 — sem perfil, sem link. */
  politicoId: string | null;
  nome: string;
  autorPrincipal: boolean;
}

export interface ProposicaoDetalhe extends Proposicao {
  autores: AutorProposicao[];
}

export interface Placar {
  sim: number;
  nao: number;
  abstencao: number;
  outros: number;
}

export interface VotacaoDetalhe {
  id: number;
  descricao: string;
  casa: CasaLegislativa;
  esfera: Esfera;
  ambito: AmbitoVotacao;
  tipo: TipoVotacao;
  /** Secreta é NOMINAL: registra quem participou, não como votou. */
  secreta: boolean;
  dataVotacao: string;
  /**
   * `null` em votação simbólica **e em secreta**: nos dois casos não há
   * contagem individual a somar, e exibir "0 a 0" sugeriria que ninguém votou.
   */
  placar: Placar | null;
  aprovada: boolean | null;
  proposicaoId: number | null;
  observacao?: string;
  urlFonte: string;
}

export type StatusExecucao = "CONCLUIDA" | "FALHA" | "EM_ANDAMENTO";

export interface StatusFonte {
  fonte: "CAMARA" | "SENADO" | "TSE" | "ALESP";
  /** Sempre da última execução BEM-SUCEDIDA, mesmo se a última falhou. */
  ultimaAtualizacao: string;
  status: StatusExecucao;
}

export interface StatusFontes {
  fontes: StatusFonte[];
}
