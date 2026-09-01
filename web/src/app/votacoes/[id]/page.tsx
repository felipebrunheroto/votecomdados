import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { listarIdsDeVotacoes, obterProposicao, obterVotacao } from "@/lib/api/cliente";
import { formatarDataHora, rotularCasa, rotularEsfera } from "@/lib/formato";
import { LinkFonteOficial } from "@/componentes/dominio/LinkFonteOficial";
import { PlacarVotacao } from "@/componentes/dominio/PlacarVotacao";

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
  const simbolica = v.tipo === "SIMBOLICA";

  return (
    <article className="space-y-8">
      <header>
        <p className="text-sm text-texto-suave">
          {formatarDataHora(v.dataVotacao)} · {rotularCasa(v.casa)} ·{" "}
          {rotularEsfera(v.esfera)}
        </p>
        <h1 className="mt-1 text-2xl font-semibold tracking-tight text-texto">
          {v.descricao}
        </h1>

        <p className="mt-3 flex flex-wrap items-center gap-2 text-sm">
          {/* O âmbito precisa ser visível: um parecer de comissão não tem o
              mesmo peso de uma deliberação de plenário, e listar os dois sem
              distinção inflaria a atuação aparente. */}
          <span className="rounded-padrao border border-borda px-2 py-0.5 text-texto-suave">
            {v.ambito === "COMISSAO" ? "Votação em comissão" : "Votação em plenário"}
          </span>
          <span className="rounded-padrao border border-borda px-2 py-0.5 text-texto-suave">
            {simbolica ? "Simbólica" : "Nominal"}
          </span>
          {v.aprovada !== null && (
            <span className="text-texto">
              Matéria {v.aprovada ? "aprovada" : "rejeitada"}
            </span>
          )}
        </p>

        <div className="mt-4">
          <LinkFonteOficial href={v.urlFonte}>Registro oficial da votação</LinkFonteOficial>
        </div>
      </header>

      <section aria-labelledby="titulo-placar">
        <h2 id="titulo-placar" className="sr-only">Placar</h2>
        {v.placar ? (
          <PlacarVotacao placar={v.placar} ambito={v.ambito} />
        ) : (
          <div className="rounded-padrao border border-dashed border-borda px-4 py-6">
            <p className="font-medium text-texto">Sem placar individual</p>
            <p className="mt-1 max-w-prose text-sm text-texto-suave">
              {v.observacao ??
                "Votação simbólica: a Casa registra apenas o resultado, não o voto de cada parlamentar."}
            </p>
            <p className="mt-2 max-w-prose text-sm text-texto-tenue">
              Isso não é uma lacuna da plataforma: o dado não existe na origem.
              Nenhum parlamentar pode ser associado a um voto nesta matéria.
            </p>
          </div>
        )}
      </section>

      {proposicao && (
        <section aria-labelledby="titulo-materia">
          <h2 id="titulo-materia" className="text-lg font-semibold text-texto">
            Matéria votada
          </h2>
          <div className="mt-3 rounded-padrao border border-borda p-4">
            <Link
              href={`/proposicoes/${proposicao.id}`}
              className="font-semibold text-acento underline underline-offset-2 hover:no-underline"
            >
              {proposicao.siglaTipo} {proposicao.numero}/{proposicao.ano}
            </Link>
            <p className="mt-1.5 text-texto-suave">{proposicao.ementa}</p>
          </div>
        </section>
      )}
    </article>
  );
}
