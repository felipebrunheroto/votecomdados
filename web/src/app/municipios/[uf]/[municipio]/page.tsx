import { notFound } from "next/navigation";
import { listarMunicipiosComEmenda, obterEmendasDoMunicipio } from "@/lib/api/cliente";
import { VistaDoMunicipio } from "@/componentes/dominio/VistaDoMunicipio";

/**
 * Só as cidades COM dado são pré-renderizadas — 474 das 5.570.
 *
 * As outras 5.096 teriam a mesma tela de "nenhuma emenda identificada" e
 * chegam pelo fallback de cliente (`not-found.tsx`), que passou a funcionar
 * com a correção de roteamento de 25/09/2026.
 *
 * Não é só economia de build: cada publicação reescreve todos os objetos, e
 * página vazia não tem o que indexar. Ver CUSTOS_INFRA_AWS.md.
 */
export async function generateStaticParams() {
  const municipios = await listarMunicipiosComEmenda();
  return municipios.map((m) => ({ uf: m.uf, municipio: m.municipio }));
}

type Props = { params: Promise<{ uf: string; municipio: string }> };

export async function generateMetadata({ params }: Props) {
  const { uf, municipio } = await params;
  const nome = decodeURIComponent(municipio);
  return {
    title: `Emendas para ${nome} — ${uf.toUpperCase()} · VoteComDados`,
    description:
      `Emendas parlamentares federais com destino a ${nome} (${uf.toUpperCase()}), ` +
      `com o que a fonte informa e o que ela não informa.`,
  };
}

export default async function PaginaDoMunicipio({ params }: Props) {
  const { uf, municipio } = await params;
  const dados = await obterEmendasDoMunicipio(uf, decodeURIComponent(municipio));

  // `null` é falha de rede, não ausência de dado: cidade sem registro vem
  // com 200 e `temRegistro: false`.
  if (!dados) notFound();

  return (
    <main className="mx-auto max-w-3xl px-4 py-8">
      <VistaDoMunicipio dados={dados} />
    </main>
  );
}
