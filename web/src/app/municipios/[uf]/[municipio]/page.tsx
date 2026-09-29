import { notFound } from "next/navigation";
import { listarDadosDeMunicipios } from "@/lib/api/cliente";
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
  const municipios = await listarDadosDeMunicipios();
  return municipios.map((m) => ({ uf: m.uf, municipio: m.municipio }));
}

type Props = { params: Promise<{ uf: string; municipio: string }> };

export async function generateMetadata({ params }: Props) {
  const { uf, municipio } = await params;
  const nome = decodeURIComponent(municipio);
  return {
    // Sem " · VoteComDados": o layout ja aplica o template "%s · VoteComDados".
    // Repetir aqui produzia "... · VoteComDados · VoteComDados", que foi o que
    // producao mostrou em 28/09/2026.
    title: `Emendas para ${nome} — ${uf.toUpperCase()}`,
    description:
      `Emendas parlamentares federais com destino a ${nome} (${uf.toUpperCase()}), ` +
      `com o que a fonte informa e o que ela não informa.`,
  };
}

export default async function PaginaDoMunicipio({ params }: Props) {
  const { uf, municipio } = await params;
  const nome = decodeURIComponent(municipio);

  // Do lote, não de uma chamada por página: com 1.596 cidades, uma chamada
  // cada estourava o limite do WAF e TODAS saíam como "não encontrada".
  const todos = await listarDadosDeMunicipios();
  const dados = todos.find(
    (m) => m.uf === uf.toUpperCase() && m.municipio === nome.toUpperCase(),
  );

  // Só se chega aqui quando generateStaticParams devolveu uma cidade que o
  // lote não contém -- incoerência real, não ausência de dado. Cidade sem
  // registro nem entra na lista: ela chega pelo fallback de cliente.
  if (!dados) notFound();

  return (
    <main className="mx-auto max-w-3xl px-4 py-8">
      <VistaDoMunicipio dados={dados} />
    </main>
  );
}
