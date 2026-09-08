import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { listarIdsDeVotacoes, obterProposicao, obterVotacao } from "@/lib/api/cliente";
import { DetalheDaVotacao } from "@/componentes/dominio/DetalheDaVotacao";

export async function generateStaticParams() {
  const ids = await listarIdsDeVotacoes();
  return ids.map((id) => ({ id: String(id) }));
}

type Props = { params: Promise<{ id: string }> };

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const { id } = await params;
  const v = await obterVotacao(Number(id));
  if (!v) return { title: "Votação não encontrada" };
  return { title: v.descricao.slice(0, 60), description: v.descricao };
}

export default async function PaginaVotacao({ params }: Props) {
  const { id } = await params;
  const v = await obterVotacao(Number(id));
  if (!v) notFound();

  const proposicao = v.proposicaoId ? await obterProposicao(v.proposicaoId) : null;
  return <DetalheDaVotacao v={v} proposicao={proposicao} />;
}
