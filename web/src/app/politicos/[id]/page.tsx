import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { listarIdsParaPreRender, obterPerfil } from "@/lib/api/cliente";
import { rotularCargo } from "@/lib/formato";
import { PerfilDoPolitico } from "@/componentes/dominio/PerfilDoPolitico";

/**
 * Com `output: "export"`, só existem as rotas devolvidas aqui — o Next força
 * `dynamicParams: false` e qualquer id fora desta lista resulta em 404, sem
 * HTML gerado.
 *
 * Em produção isso é uma restrição real: pré-renderizar os ~28 mil candidatos
 * da coorte tornaria o build diário inviável, mas pré-renderizar só quem tem
 * atuação deixaria os demais inacessíveis. A saída é um fallback no
 * hospedador (CloudFront 404 -> 200), e quem trata a página 404 como perfil
 * de verdade é `app/not-found.tsx` — a "casca renderizada no cliente" que
 * este comentário já previa. Falta só a configuração de rewrite no
 * CloudFront/Firebase (infraestrutura; nenhum IaC existe no repositório
 * ainda). Ver docs/FRONTEND.md § 1 e docs/PLANO_CORRECAO_STATIC_PARAMS.md.
 */
export async function generateStaticParams() {
  const ids = await listarIdsParaPreRender();
  return ids.map((id) => ({ id }));
}

type Props = { params: Promise<{ id: string }> };

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const { id } = await params;
  const perfil = await obterPerfil(id);
  if (!perfil) return { title: "Candidato não encontrado" };

  const nome = perfil.nomeUrna ?? perfil.nomeCivil;
  const atual = perfil.trajetoria[0];
  return {
    title: nome,
    description: `Proposições e votos de ${nome}, candidato a ${rotularCargo(
      atual.cargo,
    ).toLowerCase()} por ${atual.uf} em 2026.`,
  };
}

export default async function PaginaPerfil({ params }: Props) {
  const { id } = await params;
  const perfil = await obterPerfil(id);
  if (!perfil) notFound();

  return <PerfilDoPolitico perfil={perfil} />;
}
