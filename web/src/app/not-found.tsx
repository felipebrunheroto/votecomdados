"use client";

import { useEffect, useState, useSyncExternalStore } from "react";
import { usePathname } from "next/navigation";
import Link from "next/link";
import { obterPerfil, obterProposicao, obterVotacao } from "@/lib/api/cliente";
import type { PoliticoPerfil, ProposicaoDetalhe, VotacaoDetalhe } from "@/lib/api/tipos";
import { PerfilDoPolitico } from "@/componentes/dominio/PerfilDoPolitico";
import { DetalheDaProposicao } from "@/componentes/dominio/DetalheDaProposicao";
import { DetalheDaVotacao } from "@/componentes/dominio/DetalheDaVotacao";
import { Carregando } from "@/componentes/ui/Estados";

/** `/politicos/{uuid}` — o mesmo formato que `generateStaticParams` recebe. */
const ID_DE_POLITICO = /^\/politicos\/([0-9a-f-]{36})\/?$/;

// Matéria e votação anteriores à legislatura corrente deixaram de ser
// pré-renderizadas (ver ProposicaoRepositorio.todosOsIds): em 08/09/2026 a base
// passou de 346 mil proposições e o build estourou a pilha do Next. Elas
// continuam existindo -- só chegam por aqui, montadas no navegador.
const ID_DE_PROPOSICAO = /^\/proposicoes\/(\d+)\/?$/;
const ID_DE_VOTACAO = /^\/votacoes\/(\d+)\/?$/;

/**
 * A "casca renderizada no cliente" que `docs/FRONTEND.md` (e o comentário de
 * `politicos/[id]/page.tsx`) previam desde antes desta correção, e que nunca
 * tinha sido construída — ver `docs/PLANO_CORRECAO_STATIC_PARAMS.md`, B2.
 *
 * <h2>Por que este arquivo, e não uma rota nova</h2>
 *
 * Com `output: "export"`, só existe HTML pré-gerado para quem
 * `generateStaticParams` devolveu — a minoria dos ~28 mil candidatos com
 * atuação legislativa. Para todo o resto, o hospedador (CloudFront/Firebase)
 * é configurado para responder **200**, não 404, servindo este MESMO arquivo
 * estático (que o Next gera normalmente, por não ser uma rota dinâmica) —
 * padrão clássico de fallback de SPA. O trabalho daqui é: no navegador,
 * reconhecer que a URL era um perfil, buscar o dado e renderizar a página de
 * verdade — sem isso, o rewrite do hospedador só trocaria "404 genérico" por
 * "outra página genérica", sem nunca mostrar o perfil.
 *
 * <h2>Três rotas caem aqui, não uma</h2>
 *
 * `/politicos/{id}` sempre dependeu deste fallback: são 28 mil candidatos, e
 * pré-renderizar todos nunca fez sentido.
 *
 * `/proposicoes/{id}` e `/votacoes/{id}` pré-renderizavam **todo mundo** — e
 * isso deixou de ser possível em 08/09/2026, quando o primeiro ciclo
 * incremental completo levou a base a 346.481 proposições e o build estourou
 * a pilha do Next. Hoje o pré-render cobre a legislatura corrente (ver
 * `ProposicaoRepositorio.todosOsIds`); matéria e votação anteriores chegam por
 * aqui e são montadas no navegador.
 *
 * <p>O que elas perdem é a página pronta no HTML — indexação em buscador —,
 * não o acesso. Link antigo continua abrindo.
 *
 * <h2>Reflexo local sem depender do hospedador de verdade</h2>
 *
 * `next build && npx serve out` já serve este arquivo (200) quando pede um
 * id fora do pré-render — falta só configurar o rewrite real no
 * CloudFront/Firebase (infraestrutura, fora do escopo desta correção). Em
 * `next dev`, este componente normalmente nem chega a rodar: `output:
 * "export"` só entra durante `next build` (ver `next.config.ts`), então em
 * dev qualquer id renderiza direto pela rota, sem passar por "não
 * encontrado".
 */
export default function NaoEncontrado() {
  // O HTML deste arquivo é gerado em build time, sem saber qual URL real vai
  // reescrever pra cá — o servidor não tem como conhecer `usePathname()` de
  // antemão. Decidir com base nele já na primeira renderização faria a
  // hidratação divergir do que o servidor emitiu (React error #418).
  // `montado` adia essa decisão para DEPOIS da hidratação: o servidor (e a
  // primeira renderização no cliente, antes de hidratar) sempre veem `false`
  // — igual ao HTML gerado em build time —, só depois de hidratar é que
  // passa a `true`. `useSyncExternalStore` é o jeito idiomático de expressar
  // isso sem cair em "setState dentro de efeito" (o `useEffect` + `useState`
  // equivalente dispara um render em cascata que o eslint-plugin-react-hooks
  // acusa).
  const montado = useSyncExternalStore(
    () => () => {},
    () => true,
    () => false,
  );

  const caminho = usePathname();
  const idDeCandidato = montado ? caminho.match(ID_DE_POLITICO)?.[1] : undefined;
  const idDeProposicao = montado ? caminho.match(ID_DE_PROPOSICAO)?.[1] : undefined;
  const idDeVotacao = montado ? caminho.match(ID_DE_VOTACAO)?.[1] : undefined;

  if (idDeCandidato) return <CascaDePerfil id={idDeCandidato} />;
  if (idDeProposicao) return <CascaDeProposicao id={idDeProposicao} />;
  if (idDeVotacao) return <CascaDeVotacao id={idDeVotacao} />;

  return (
    <div className="py-12 text-center">
      <h1 className="text-2xl font-semibold text-texto">Página não encontrada</h1>
      <p className="mx-auto mt-2 max-w-prose text-texto-suave">
        O endereço acessado não existe. Se você procurava um candidato, use a
        busca — a plataforma cobre apenas quem tem registro de candidatura em
        2026.
      </p>
      <Link
        href="/"
        className="mt-5 inline-block rounded-padrao bg-acento px-4 py-2 font-medium text-acento-contraste"
      >
        Ir para a busca
      </Link>
    </div>
  );
}

/**
 * A mesma tela da rota pré-renderizada, montada no navegador -- por isso o
 * componente de apresentação é compartilhado, e não copiado.
 */
function CascaDeProposicao({ id }: { id: string }) {
  const [proposicao, setProposicao] =
    useState<ProposicaoDetalhe | null | undefined>(undefined);

  useEffect(() => {
    let cancelado = false;
    obterProposicao(Number(id)).then((p) => { if (!cancelado) setProposicao(p); });
    return () => { cancelado = true; };
  }, [id]);

  if (proposicao === undefined) {
    return <Carregando rotulo="Carregando proposição…" />;
  }

  if (proposicao === null) {
    return (
      <div className="py-12 text-center">
        <h1 className="text-2xl font-semibold text-texto">Proposição não encontrada</h1>
        <p className="mx-auto mt-2 max-w-prose text-texto-suave">
          Não há matéria com este identificador.
        </p>
        <Link
          href="/"
          className="mt-5 inline-block rounded-padrao bg-acento px-4 py-2 font-medium text-acento-contraste"
        >
          Ir para a busca
        </Link>
      </div>
    );
  }

  return <DetalheDaProposicao p={proposicao} />;
}

function CascaDeVotacao({ id }: { id: string }) {
  const [dados, setDados] = useState<
    { v: VotacaoDetalhe; proposicao: ProposicaoDetalhe | null } | null | undefined
  >(undefined);

  useEffect(() => {
    let cancelado = false;
    obterVotacao(Number(id))
      .then(async (v) => {
        if (!v) return null;
        // A tela mostra a matéria votada; buscar em seguida, e não em
        // paralelo, porque o id dela vem da própria votação.
        const p = v.proposicaoId ? await obterProposicao(v.proposicaoId) : null;
        return { v, proposicao: p };
      })
      .then((d) => { if (!cancelado) setDados(d); });
    return () => { cancelado = true; };
  }, [id]);

  if (dados === undefined) return <Carregando rotulo="Carregando votação…" />;

  if (dados === null) {
    return (
      <div className="py-12 text-center">
        <h1 className="text-2xl font-semibold text-texto">Votação não encontrada</h1>
        <p className="mx-auto mt-2 max-w-prose text-texto-suave">
          Não há votação com este identificador.
        </p>
        <Link
          href="/"
          className="mt-5 inline-block rounded-padrao bg-acento px-4 py-2 font-medium text-acento-contraste"
        >
          Ir para a busca
        </Link>
      </div>
    );
  }

  return <DetalheDaVotacao v={dados.v} proposicao={dados.proposicao} />;
}

function CascaDePerfil({ id }: { id: string }) {
  const [perfil, setPerfil] = useState<PoliticoPerfil | null | undefined>(undefined);

  useEffect(() => {
    let cancelado = false;
    obterPerfil(id).then((p) => { if (!cancelado) setPerfil(p); });
    return () => { cancelado = true; };
  }, [id]);

  if (perfil === undefined) {
    return <Carregando rotulo="Carregando perfil do candidato…" />;
  }

  if (perfil === null) {
    return (
      <div className="py-12 text-center">
        <h1 className="text-2xl font-semibold text-texto">Candidato não encontrado</h1>
        <p className="mx-auto mt-2 max-w-prose text-texto-suave">
          Não há registro de candidatura em 2026 com este identificador.
        </p>
        <Link
          href="/"
          className="mt-5 inline-block rounded-padrao bg-acento px-4 py-2 font-medium text-acento-contraste"
        >
          Ir para a busca
        </Link>
      </div>
    );
  }

  return <PerfilDoPolitico perfil={perfil} />;
}
