/**
 * Dados de desenvolvimento enquanto a API não existe.
 *
 * Deliberadamente povoado com os CASOS DIFÍCEIS, não com o caminho feliz —
 * são eles que quebram layout e revelam texto ambíguo:
 *
 *   1. candidato sem nenhum mandato anterior (a maioria dos ~28 mil da coorte);
 *   2. trajetória atravessando municipal → estadual → federal;
 *   3. voto de comissão da Alesp, que não é voto de plenário;
 *   4. votação simbólica, sem voto individual;
 *   5. "obstrução" e "ausente", que não são posição sobre o mérito;
 *   6. mandato anterior a 2001, sem votos nominais na fonte;
 *   7. candidatura indeferida, que continua visível com o status.
 *
 * Ementas de proposições vêm de dados reais da Câmara.
 */
import type {
  AutorProposicao, Cobertura, Pagina, PoliticoPerfil, PoliticoResumo, Proposicao,
  StatusFontes, VotacaoDetalhe, VotacaoDoPolitico,
} from "./tipos";

const COBERTURA_FEDERAL: Cobertura[] = [
  { esfera: "FEDERAL", uf: null, casa: "CAMARA", recurso: "proposicao", status: "DISPONIVEL",
    disponivelDesde: "1934-01-01",
    observacao: "Proposições da Câmara dos Deputados disponíveis desde 1934." },
  { esfera: "FEDERAL", uf: null, casa: "CAMARA", recurso: "voto_nominal", status: "DISPONIVEL",
    disponivelDesde: "2001-01-01",
    observacao: "Votos nominais individuais de plenário só existem a partir de 2001; mandatos anteriores não têm registro individual publicado." },
];

const COBERTURA_SP: Cobertura[] = [
  { esfera: "ESTADUAL", uf: "SP", casa: "ALESP", recurso: "proposicao", status: "DISPONIVEL",
    disponivelDesde: "1995-01-01",
    observacao: "Proposituras e autoria no portal de dados abertos da Alesp." },
  { esfera: "ESTADUAL", uf: "SP", casa: "ALESP", recurso: "votacao_comissao", status: "DISPONIVEL",
    disponivelDesde: "1995-01-01",
    observacao: "Votos individuais em comissões permanentes da Alesp." },
  { esfera: "ESTADUAL", uf: "SP", casa: "ALESP", recurso: "voto_nominal", status: "NAO_PUBLICADO_PELA_FONTE",
    disponivelDesde: null,
    observacao: "A Alesp não publica os votos nominais de plenário em dados abertos. A ausência é da fonte, não da plataforma." },
];

const COBERTURA_OUTRO_ESTADO: Cobertura[] = [
  { esfera: "ESTADUAL", uf: null, casa: null, recurso: "proposicao", status: "FORA_DO_ESCOPO_MVP",
    disponivelDesde: null,
    observacao: "Nesta versão só a Assembleia de São Paulo está integrada. As demais assembleias ainda não foram cobertas." },
];

const COBERTURA_MUNICIPAL: Cobertura[] = [
  { esfera: "MUNICIPAL", uf: null, casa: null, recurso: "proposicao", status: "FORA_DO_ESCOPO_MVP",
    disponivelDesde: null,
    observacao: "A atuação em câmaras municipais não é coberta nesta versão. As 5.570 câmaras não publicam dados legislativos em formato padronizado." },
];

export const PERFIS: PoliticoPerfil[] = [
  { id: "a1000000-0000-4000-8000-000000000001",
    nomeCivil: "Adriana Ventura Nogueira", nomeUrna: "Adriana Ventura",
    possuiAtuacaoLegislativa: true,
    trajetoria: [
      { anoEleicao: 2026, cargo: "GOVERNADOR", esfera: "ESTADUAL", uf: "SP", municipio: null, partidoSigla: "NOVO", status: "DEFERIDO", eleito: null },
      { anoEleicao: 2022, cargo: "DEPUTADO_FEDERAL", esfera: "FEDERAL", uf: "SP", municipio: null, partidoSigla: "NOVO", status: "DEFERIDO", eleito: true },
      { anoEleicao: 2018, cargo: "DEPUTADO_ESTADUAL", esfera: "ESTADUAL", uf: "SP", municipio: null, partidoSigla: "NOVO", status: "DEFERIDO", eleito: true },
      { anoEleicao: 2016, cargo: "VEREADOR", esfera: "MUNICIPAL", uf: "SP", municipio: "Campinas", partidoSigla: "PSDB", status: "DEFERIDO", eleito: true },
    ],
    cobertura: [...COBERTURA_FEDERAL, ...COBERTURA_SP, ...COBERTURA_MUNICIPAL] },

  { id: "a1000000-0000-4000-8000-000000000002",
    nomeCivil: "Joana Ribeiro Alcântara", nomeUrna: "Joana Alcântara",
    possuiAtuacaoLegislativa: false,
    trajetoria: [
      { anoEleicao: 2026, cargo: "DEPUTADO_FEDERAL", esfera: "FEDERAL", uf: "MG", municipio: null, partidoSigla: "PSB", status: "DEFERIDO", eleito: null },
    ],
    cobertura: [...COBERTURA_FEDERAL, ...COBERTURA_OUTRO_ESTADO, ...COBERTURA_MUNICIPAL] },

  { id: "a1000000-0000-4000-8000-000000000003",
    nomeCivil: "Adilson Barroso Pinto", nomeUrna: "Adilson Barroso",
    possuiAtuacaoLegislativa: true,
    trajetoria: [
      { anoEleicao: 2026, cargo: "SENADOR", esfera: "FEDERAL", uf: "SP", municipio: null, partidoSigla: "PL", status: "INDEFERIDO", eleito: null },
      { anoEleicao: 1998, cargo: "DEPUTADO_FEDERAL", esfera: "FEDERAL", uf: "SP", municipio: null, partidoSigla: "PL", status: "DEFERIDO", eleito: true },
      { anoEleicao: 1996, cargo: "VEREADOR", esfera: "MUNICIPAL", uf: "SP", municipio: "São Paulo", partidoSigla: "PL", status: "DEFERIDO", eleito: true },
    ],
    cobertura: [...COBERTURA_FEDERAL, ...COBERTURA_SP, ...COBERTURA_MUNICIPAL] },

  { id: "a1000000-0000-4000-8000-000000000004",
    nomeCivil: "Adolfo Viana de Castro Neto", nomeUrna: "Adolfo Viana",
    possuiAtuacaoLegislativa: true,
    trajetoria: [
      { anoEleicao: 2026, cargo: "DEPUTADO_FEDERAL", esfera: "FEDERAL", uf: "BA", municipio: null, partidoSigla: "PSDB", status: "DEFERIDO", eleito: null },
      { anoEleicao: 2022, cargo: "DEPUTADO_FEDERAL", esfera: "FEDERAL", uf: "BA", municipio: null, partidoSigla: "PSDB", status: "DEFERIDO", eleito: true },
      { anoEleicao: 2018, cargo: "DEPUTADO_ESTADUAL", esfera: "ESTADUAL", uf: "BA", municipio: null, partidoSigla: "PSDB", status: "DEFERIDO", eleito: true },
    ],
    cobertura: [...COBERTURA_FEDERAL, ...COBERTURA_OUTRO_ESTADO, ...COBERTURA_MUNICIPAL] },

  { id: "a1000000-0000-4000-8000-000000000005",
    nomeCivil: "Acácio Favacho Rodrigues", nomeUrna: "Acácio Favacho",
    // Sem proposições nem votos abaixo — "true" aqui afirmaria atuação que
    // não existe. O backend deriva esta coluna de autoria/voto/mandato
    // (marcar_atuacao_legislativa, achado A1); a fixture precisa concordar.
    possuiAtuacaoLegislativa: false,
    trajetoria: [
      { anoEleicao: 2026, cargo: "SENADOR", esfera: "FEDERAL", uf: "AP", municipio: null, partidoSigla: "MDB", status: "DEFERIDO", eleito: null },
      { anoEleicao: 2022, cargo: "DEPUTADO_FEDERAL", esfera: "FEDERAL", uf: "AP", municipio: null, partidoSigla: "MDB", status: "DEFERIDO", eleito: true },
    ],
    cobertura: [...COBERTURA_FEDERAL, ...COBERTURA_OUTRO_ESTADO, ...COBERTURA_MUNICIPAL] },

  { id: "a1000000-0000-4000-8000-000000000006",
    nomeCivil: "Adriano Augusto do Baldy", nomeUrna: "Adriano do Baldy",
    possuiAtuacaoLegislativa: false,
    trajetoria: [
      { anoEleicao: 2026, cargo: "GOVERNADOR", esfera: "ESTADUAL", uf: "GO", municipio: null, partidoSigla: "PP", status: "DEFERIDO", eleito: null },
    ],
    cobertura: [...COBERTURA_FEDERAL, ...COBERTURA_OUTRO_ESTADO, ...COBERTURA_MUNICIPAL] },
];

export const RESUMOS: PoliticoResumo[] = PERFIS.map((p) => {
  const atual = p.trajetoria[0];
  return {
    id: p.id, nomeCivil: p.nomeCivil, nomeUrna: p.nomeUrna,
    cargo2026: atual.cargo, uf: atual.uf, partidoSigla: atual.partidoSigla,
    statusCandidatura: atual.status,
    possuiAtuacaoLegislativa: p.possuiAtuacaoLegislativa,
  };
});

export const PROPOSICOES: Record<string, Proposicao[]> = {
  "a1000000-0000-4000-8000-000000000001": [
    { id: 1197773, casa: "CAMARA", esfera: "FEDERAL", siglaTipo: "PL", numero: 4015, ano: 2023,
      ementa: "Altera o art. 121 do Decreto-Lei nº 2.848, de 7 de dezembro de 1940 - Código Penal - para prever como homicídio qualificado o crime cometido contra agente de saúde em serviço.",
      temas: ["Direito Penal e Processual Penal", "Saúde"],
      dataApresentacao: "2023-08-14", situacaoAtual: "Aguardando Parecer do Relator",
      urlInteiroTeor: "https://www.camara.leg.br/proposicoesWeb/prop_mostrarintegra?codteor=1",
      urlTramitacao: "https://www.camara.leg.br/proposicoesWeb/fichadetramitacao?idProposicao=1197773" },
    { id: 618609, casa: "CAMARA", esfera: "FEDERAL", siglaTipo: "PL", numero: 6155, ano: 2023,
      ementa: 'Institui o dia 25 de julho como o "Dia Nacional da Cultura e da Paz", e dá outras providências.',
      temas: ["Cultura"], dataApresentacao: "2023-11-30", situacaoAtual: "Arquivada",
      urlInteiroTeor: null,
      urlTramitacao: "https://www.camara.leg.br/proposicoesWeb/fichadetramitacao?idProposicao=618609" },
    { id: 900001, casa: "ALESP", esfera: "ESTADUAL", siglaTipo: "PL", numero: 512, ano: 2019,
      ementa: "Dispõe sobre a obrigatoriedade de divulgação, em sítio eletrônico, das obras públicas estaduais em andamento no Estado de São Paulo.",
      temas: ["Administração Pública", "Transparência"],
      dataApresentacao: "2019-05-22", situacaoAtual: "Aprovada em Comissão",
      urlInteiroTeor: null, urlTramitacao: "https://www.al.sp.gov.br/propositura/?id=900001" },
  ],
  "a1000000-0000-4000-8000-000000000003": [
    { id: 369205, casa: "CAMARA", esfera: "FEDERAL", siglaTipo: "PL", numero: 4089, ano: 1999,
      ementa: "Torna obrigatória a homologação em cartório de todo contrato de empréstimo consignado a ser efetuado por aposentado ou pensionista.",
      temas: ["Direito Civil e Processual Civil", "Previdência"],
      dataApresentacao: "1999-03-10", situacaoAtual: "Arquivada",
      urlInteiroTeor: null,
      urlTramitacao: "https://www.camara.leg.br/proposicoesWeb/fichadetramitacao?idProposicao=369205" },
  ],
  "a1000000-0000-4000-8000-000000000004": [
    { id: 2074843, casa: "CAMARA", esfera: "FEDERAL", siglaTipo: "PL", numero: 6064, ano: 2023,
      ementa: "Dispõe sobre direito a dano moral e concessão de pensão especial à pessoa com Microcefalia ou com Síndrome Congênita decorrente do Zika vírus.",
      temas: ["Saúde", "Direitos Humanos"],
      dataApresentacao: "2023-11-21", situacaoAtual: "Pronta para Pauta",
      urlInteiroTeor: null,
      urlTramitacao: "https://www.camara.leg.br/proposicoesWeb/fichadetramitacao?idProposicao=2074843" },
  ],
  "a1000000-0000-4000-8000-000000000005": [],
};

export const VOTACOES: Record<string, VotacaoDoPolitico[]> = {
  "a1000000-0000-4000-8000-000000000001": [
    { votacaoId: 555111, dataVotacao: "2023-06-15T17:32:00Z",
      descricao: "Aprovação do requerimento de urgência para o PL 4015/2023",
      casa: "CAMARA", esfera: "FEDERAL", ambito: "PLENARIO",
      temas: ["Direito Penal e Processual Penal"], tipo: "NOMINAL", secreta: false,
      voto: "SIM", votoOrigem: "Sim", origemRegistro: "FONTE", aprovada: true,
      urlFonte: "https://www.camara.leg.br/votacoes/555111" },
    { votacaoId: 555113, dataVotacao: "2023-06-18T19:40:00Z",
      descricao: "Votação em turno único do PL 6064/2023",
      casa: "CAMARA", esfera: "FEDERAL", ambito: "PLENARIO",
      temas: ["Saúde"], tipo: "NOMINAL", secreta: false,
      voto: "OBSTRUCAO", votoOrigem: "Obstrução", origemRegistro: "FONTE",
      notaMetodologica: "Obstrução é manobra regimental de orientação de bancada, não um voto contrário ao mérito.",
      aprovada: false, urlFonte: "https://www.camara.leg.br/votacoes/555113" },
    { votacaoId: 555112, dataVotacao: "2023-06-20T13:05:00Z",
      descricao: "Redação final do PL 6155/2023",
      casa: "CAMARA", esfera: "FEDERAL", ambito: "PLENARIO",
      temas: ["Cultura"], tipo: "SIMBOLICA", secreta: false, voto: null, votoOrigem: null,
      origemRegistro: null,
      observacao: "Votação simbólica: a Casa não registra o voto individual de cada parlamentar.",
      aprovada: true, urlFonte: "https://www.camara.leg.br/votacoes/555112" },
    { votacaoId: 777001, dataVotacao: "2021-04-07T14:00:00Z",
      descricao: "Parecer do relator na Comissão de Constituição, Justiça e Redação",
      casa: "ALESP", esfera: "ESTADUAL", ambito: "COMISSAO",
      temas: ["Administração Pública"], tipo: "NOMINAL", secreta: false,
      voto: "SIM", votoOrigem: "Favorável ao parecer", origemRegistro: "FONTE",
      notaMetodologica: "Voto em comissão, favorável ao parecer do relator — não é votação de plenário.",
      aprovada: true, urlFonte: "https://www.al.sp.gov.br/votacao/777001" },
    { votacaoId: 555114, dataVotacao: "2023-09-05T18:10:00Z",
      descricao: "Emenda nº 3 ao PL 2234/2023",
      casa: "CAMARA", esfera: "FEDERAL", ambito: "PLENARIO",
      temas: ["Orçamento"], tipo: "NOMINAL", secreta: false,
      // A Câmara NÃO publica ausência: esta linha é derivada do cruzamento
      // com quem estava em exercício na data — por isso votoOrigem é null.
      voto: "AUSENTE", votoOrigem: null, origemRegistro: "DERIVADO",
      notaMetodologica: "A Casa publica apenas quem registrou voto. A ausência é apurada pela plataforma, cruzando a votação com a lista de parlamentares em exercício; a fonte não informa o motivo.",
      aprovada: true, urlFonte: "https://www.camara.leg.br/votacoes/555114" },
  ],
  "a1000000-0000-4000-8000-000000000004": [
    { votacaoId: 556001, dataVotacao: "2023-10-11T16:20:00Z",
      descricao: "Votação em turno único do PL 6064/2023",
      casa: "CAMARA", esfera: "FEDERAL", ambito: "PLENARIO",
      temas: ["Saúde"], tipo: "NOMINAL", secreta: false,
      voto: "NAO", votoOrigem: "Não", origemRegistro: "FONTE", aprovada: false,
      urlFonte: "https://www.camara.leg.br/votacoes/556001" },
  ],
  "a1000000-0000-4000-8000-000000000003": [],
  "a1000000-0000-4000-8000-000000000005": [],
};

export function paginar<T>(itens: T[], page = 1, pageSize = 20): Pagina<T> {
  const inicio = (page - 1) * pageSize;
  return {
    data: itens.slice(inicio, inicio + pageSize),
    pagination: { page, pageSize, total: itens.length },
  };
}

/* ===========================================================================
   Detalhes de proposição e votação.
   =========================================================================== */

/**
 * Autoria com coautores FORA da coorte (`politicoId: null`).
 *
 * É o caso que a UI erra com facilidade: omitir esses nomes distorceria o
 * registro da matéria, e criar link para eles levaria a um perfil que não
 * existe — quem não é candidato em 2026 não tem registro pessoal na base.
 */
export const DETALHES_PROPOSICAO: Record<number, AutorProposicao[]> = {
  1197773: [
    { politicoId: "a1000000-0000-4000-8000-000000000001", nome: "Adriana Ventura", autorPrincipal: true },
    { politicoId: "a1000000-0000-4000-8000-000000000004", nome: "Adolfo Viana", autorPrincipal: false },
    { politicoId: null, nome: "Reginaldo Tavares de Almeida", autorPrincipal: false },
    { politicoId: null, nome: "Marta Figueiró Bastos", autorPrincipal: false },
  ],
  618609: [
    { politicoId: "a1000000-0000-4000-8000-000000000001", nome: "Adriana Ventura", autorPrincipal: true },
  ],
  900001: [
    { politicoId: "a1000000-0000-4000-8000-000000000001", nome: "Adriana Ventura", autorPrincipal: true },
    { politicoId: null, nome: "Carlos Eduardo Pignatari", autorPrincipal: false },
  ],
  369205: [
    { politicoId: "a1000000-0000-4000-8000-000000000003", nome: "Adilson Barroso", autorPrincipal: true },
  ],
  2074843: [
    { politicoId: "a1000000-0000-4000-8000-000000000004", nome: "Adolfo Viana", autorPrincipal: true },
    { politicoId: null, nome: "Helena Mourão de Lima", autorPrincipal: false },
  ],
};

export const VOTACOES_DETALHE: Record<number, VotacaoDetalhe> = {
  555111: {
    id: 555111, descricao: "Aprovação do requerimento de urgência para o PL 4015/2023",
    casa: "CAMARA", esfera: "FEDERAL", ambito: "PLENARIO", tipo: "NOMINAL", secreta: false,
    dataVotacao: "2023-06-15T17:32:00Z",
    placar: { sim: 312, nao: 145, abstencao: 3, outros: 53 },
    aprovada: true, proposicaoId: 1197773,
    urlFonte: "https://www.camara.leg.br/votacoes/555111",
  },
  555113: {
    id: 555113, descricao: "Votação em turno único do PL 6064/2023",
    casa: "CAMARA", esfera: "FEDERAL", ambito: "PLENARIO", tipo: "NOMINAL", secreta: false,
    dataVotacao: "2023-06-18T19:40:00Z",
    placar: { sim: 201, nao: 248, abstencao: 7, outros: 57 },
    aprovada: false, proposicaoId: 2074843,
    urlFonte: "https://www.camara.leg.br/votacoes/555113",
  },
  555112: {
    // Simbólica: sem placar individual — o caso que a UI não pode apresentar
    // como "zero votos".
    id: 555112, descricao: "Redação final do PL 6155/2023",
    casa: "CAMARA", esfera: "FEDERAL", ambito: "PLENARIO", tipo: "SIMBOLICA", secreta: false,
    dataVotacao: "2023-06-20T13:05:00Z",
    placar: null, aprovada: true, proposicaoId: 618609,
    observacao: "Votação simbólica: a Casa não registra o voto individual de cada parlamentar, apenas o resultado.",
    urlFonte: "https://www.camara.leg.br/votacoes/555112",
  },
  777001: {
    id: 777001, descricao: "Parecer do relator na Comissão de Constituição, Justiça e Redação",
    casa: "ALESP", esfera: "ESTADUAL", ambito: "COMISSAO", tipo: "NOMINAL", secreta: false,
    dataVotacao: "2021-04-07T14:00:00Z",
    placar: { sim: 8, nao: 2, abstencao: 0, outros: 1 },
    aprovada: true, proposicaoId: 900001,
    urlFonte: "https://www.al.sp.gov.br/votacao/777001",
  },
  555114: {
    id: 555114, descricao: "Emenda nº 3 ao PL 2234/2023",
    casa: "CAMARA", esfera: "FEDERAL", ambito: "PLENARIO", tipo: "NOMINAL", secreta: false,
    dataVotacao: "2023-09-05T18:10:00Z",
    placar: { sim: 289, nao: 160, abstencao: 4, outros: 60 },
    aprovada: true, proposicaoId: null,
    urlFonte: "https://www.camara.leg.br/votacoes/555114",
  },
  556001: {
    id: 556001, descricao: "Votação em turno único do PL 6064/2023",
    casa: "CAMARA", esfera: "FEDERAL", ambito: "PLENARIO", tipo: "NOMINAL", secreta: false,
    dataVotacao: "2023-10-11T16:20:00Z",
    placar: { sim: 190, nao: 260, abstencao: 5, outros: 58 },
    aprovada: false, proposicaoId: 2074843,
    urlFonte: "https://www.camara.leg.br/votacoes/556001",
  },
};

/** Uma fonte em FALHA de propósito: a UI precisa dizer que o dado está velho. */
export const STATUS_FONTES: StatusFontes = {
  fontes: [
    { fonte: "CAMARA", ultimaAtualizacao: "2026-08-18T04:12:33Z", status: "CONCLUIDA" },
    { fonte: "SENADO", ultimaAtualizacao: "2026-08-18T04:15:02Z", status: "CONCLUIDA" },
    { fonte: "ALESP", ultimaAtualizacao: "2026-08-15T03:40:19Z", status: "FALHA" },
    { fonte: "TSE", ultimaAtualizacao: "2026-08-17T02:00:11Z", status: "CONCLUIDA" },
  ],
};

/** Todas as proposições, achatadas — usado por `generateStaticParams`. */
export const TODAS_PROPOSICOES: Proposicao[] = Object.values(PROPOSICOES).flat();
