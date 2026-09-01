"use client";

import { useEffect, useState } from "react";
import { listarProposicoes, listarVotacoes } from "@/lib/api/cliente";
import type { Candidatura, Cobertura, Proposicao, VotacaoDoPolitico } from "@/lib/api/tipos";
import { Abas } from "../ui/Abas";
import { Carregando, Erro, Vazio } from "../ui/Estados";
import { ListaProposicoes } from "./ListaProposicoes";
import { ListaVotacoes } from "./ListaVotacoes";

/**
 * Explica uma aba vazia usando a cobertura declarada pela API.
 *
 * É o ponto onde a plataforma mais facilmente enganaria: lista vazia sem texto
 * leva o leitor a concluir "não fez nada". Aqui a ausência sempre vem com
 * motivo — limite da fonte, limite do escopo, ou realmente nenhum registro.
 *
 * A cobertura é filtrada pelas esferas onde a pessoa REALMENTE teve mandato.
 * Sem esse filtro a explicação vira outra mentira: um deputado federal dos
 * anos 90 receberia "a Alesp não publica votos de plenário" só por ser de São
 * Paulo, atribuindo a lacuna a uma Casa onde ele nunca atuou.
 */
function explicarVazio(
  cobertura: Cobertura[],
  recursos: string[],
  trajetoria: Candidatura[],
): { titulo: string; descricao: string } {
  const esferasComMandato = new Set(
    trajetoria.filter((c) => c.eleito === true).map((c) => c.esfera),
  );

  const relevantes = cobertura.filter(
    (c) => recursos.includes(c.recurso) && esferasComMandato.has(c.esfera),
  );

  // Quem tem mandato coberto mas nada no período recebe o limite temporal;
  // ele explica mais do que "a fonte não publica" de outra esfera.
  const comLimite = relevantes.find((c) => c.status === "DISPONIVEL" && c.disponivelDesde);
  if (comLimite) {
    return {
      titulo: "Nenhum registro no período coberto pela fonte",
      descricao: comLimite.observacao,
    };
  }

  const naoPublicado = relevantes.find((c) => c.status === "NAO_PUBLICADO_PELA_FONTE");
  if (naoPublicado) {
    return { titulo: "A fonte oficial não publica estes dados", descricao: naoPublicado.observacao };
  }

  const foraDoEscopo = relevantes.find((c) => c.status === "FORA_DO_ESCOPO_MVP");
  if (foraDoEscopo) {
    return { titulo: "Ainda não cobrimos esta fonte", descricao: foraDoEscopo.observacao };
  }

  return {
    titulo: "Nenhum registro encontrado",
    descricao:
      "Não há registros para este candidato nas fontes cobertas pela plataforma.",
  };
}

interface Contexto {
  id: string;
  cobertura: Cobertura[];
  trajetoria: Candidatura[];
}

type EstadoCarga = "carregando" | "pronto" | "erro";

/**
 * Carrega uma lista paginada da API.
 *
 * O prefixo `use` não é escolha de nomenclatura: é o que faz o React aplicar
 * as Regras dos Hooks a esta função. Um nome em português quebraria a
 * verificação estática silenciosamente.
 *
 * O reset ao trocar de chave acontece DURANTE a renderização, não dentro do
 * efeito — chamar `setState` de forma síncrona num efeito dispara uma segunda
 * renderização desnecessária e é erro no React 19.
 */
function useRecurso<T>(carregar: () => Promise<{ data: T[] }>, chave: string) {
  const [itens, setItens] = useState<T[] | null>(null);
  const [estado, setEstado] = useState<EstadoCarga>("carregando");
  const [tentativa, setTentativa] = useState(0);
  const [chaveAtual, setChaveAtual] = useState(chave);

  if (chave !== chaveAtual) {
    setChaveAtual(chave);
    setItens(null);
    setEstado("carregando");
  }

  useEffect(() => {
    let cancelado = false;

    carregar()
      .then((r) => {
        if (cancelado) return;
        setItens(r.data);
        setEstado("pronto");
      })
      .catch(() => {
        if (!cancelado) setEstado("erro");
      });

    return () => { cancelado = true; };
    // `carregar` é recriada a cada renderização; a chave é o que identifica
    // de verdade a requisição.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [chave, tentativa]);

  return {
    itens,
    estado,
    tentarNovamente: () => {
      setEstado("carregando");
      setTentativa((n) => n + 1);
    },
  };
}

function PainelProposicoes({ id, cobertura, trajetoria }: Contexto) {
  const { itens, estado, tentarNovamente } = useRecurso<Proposicao>(
    () => listarProposicoes(id), `prop-${id}`,
  );

  if (estado === "carregando") return <Carregando rotulo="Carregando proposições" />;
  if (estado === "erro") return <Erro aoTentarNovamente={tentarNovamente} />;
  if (!itens || itens.length === 0) {
    const { titulo, descricao } = explicarVazio(cobertura, ["proposicao"], trajetoria);
    return <Vazio titulo={titulo} descricao={descricao} />;
  }
  return <ListaProposicoes itens={itens} />;
}

function PainelVotacoes({ id, cobertura, trajetoria }: Contexto) {
  const { itens, estado, tentarNovamente } = useRecurso<VotacaoDoPolitico>(
    () => listarVotacoes(id), `vot-${id}`,
  );

  if (estado === "carregando") return <Carregando rotulo="Carregando votações" />;
  if (estado === "erro") return <Erro aoTentarNovamente={tentarNovamente} />;
  if (!itens || itens.length === 0) {
    const { titulo, descricao } = explicarVazio(cobertura, ["voto_nominal", "votacao_comissao"], trajetoria);
    return <Vazio titulo={titulo} descricao={descricao} />;
  }
  return <ListaVotacoes itens={itens} />;
}

export function AbasDeAtuacao({ id, cobertura, trajetoria }: Contexto) {
  return (
    <Abas
      rotuloLista="Atuação legislativa"
      abas={[
        {
          id: "proposicoes",
          rotulo: "Projetos apresentados",
          conteudo: <PainelProposicoes id={id} cobertura={cobertura} trajetoria={trajetoria} />,
        },
        {
          id: "votacoes",
          rotulo: "Votações",
          conteudo: <PainelVotacoes id={id} cobertura={cobertura} trajetoria={trajetoria} />,
        },
      ]}
    />
  );
}
