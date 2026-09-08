import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { listarIdsDeProposicoes, obterProposicao } from "@/lib/api/cliente";
import { DetalheDaProposicao } from "@/componentes/dominio/DetalheDaProposicao";
import { designarProposicao } from "@/lib/formato";

/** Mesma restrição de export estático da página de perfil — ver FRONTEND.md § 1. */
export async function generateStaticParams() {
  const ids = await listarIdsDeProposicoes();
  return ids.map((id) => ({ id: String(id) }));
}

type Props = { params: Promise<{ id: string }> };

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const { id } = await params;
  const p = await obterProposicao(Number(id));
  if (!p) return { title: "Proposição não encontrada" };
  return {
    title: designarProposicao(p),
    description: p.ementa.slice(0, 160),
  };
}

export default async function PaginaProposicao({ params }: Props) {
  const { id } = await params;
  const p = await obterProposicao(Number(id));
  if (!p) notFound();

  return <DetalheDaProposicao p={p} />;
}
