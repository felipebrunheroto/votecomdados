import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { listarIdsDeProposicoes, obterProposicao } from "@/lib/api/cliente";
import { formatarData, rotularCasa, rotularEsfera } from "@/lib/formato";
import { LinkFonteOficial } from "@/componentes/dominio/LinkFonteOficial";

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
    title: `${p.siglaTipo} ${p.numero}/${p.ano}`,
    description: p.ementa.slice(0, 160),
  };
}

export default async function PaginaProposicao({ params }: Props) {
  const { id } = await params;
  const p = await obterProposicao(Number(id));
  if (!p) notFound();

  const principais = p.autores.filter((a) => a.autorPrincipal);
  const demais = p.autores.filter((a) => !a.autorPrincipal);

  return (
    <article className="space-y-8">
      <header>
        <p className="text-sm text-texto-suave">
          {rotularCasa(p.casa)} · {rotularEsfera(p.esfera)}
        </p>
        <h1 className="mt-1 text-2xl font-semibold tracking-tight text-texto">
          {p.siglaTipo} {p.numero}/{p.ano}
        </h1>
        <p className="mt-3 max-w-prose text-texto-suave">{p.ementa}</p>

        <dl className="mt-4 flex flex-wrap gap-x-8 gap-y-2 text-sm">
          <div>
            <dt className="text-texto-tenue">Apresentada em</dt>
            <dd className="text-texto">{formatarData(p.dataApresentacao)}</dd>
          </div>
          {p.situacaoAtual && (
            <div>
              <dt className="text-texto-tenue">Situação</dt>
              <dd className="text-texto">{p.situacaoAtual}</dd>
            </div>
          )}
        </dl>

        {p.temas.length > 0 && (
          <ul className="mt-3 flex flex-wrap gap-1.5" aria-label="Temas">
            {p.temas.map((t) => (
              <li key={t} className="rounded-padrao bg-fundo-sutil px-2 py-0.5 text-xs text-texto-suave">
                {t}
              </li>
            ))}
          </ul>
        )}

        <div className="mt-4 flex flex-wrap gap-x-4 gap-y-2">
          <LinkFonteOficial href={p.urlTramitacao}>Tramitação oficial</LinkFonteOficial>
          {p.urlInteiroTeor && (
            <LinkFonteOficial href={p.urlInteiroTeor}>Inteiro teor</LinkFonteOficial>
          )}
        </div>
      </header>

      <section aria-labelledby="titulo-autoria">
        <h2 id="titulo-autoria" className="text-lg font-semibold text-texto">
          Autoria
        </h2>
        <p className="mt-1 max-w-prose text-sm text-texto-suave">
          A lista reproduz integralmente a autoria registrada na fonte oficial.
          Só têm página no site quem é candidato em 2026 — os demais aparecem
          apenas pelo nome, porque a plataforma não mantém registro de quem não
          está concorrendo.
        </p>

        <ul className="mt-4 space-y-2">
          {[...principais, ...demais].map((a) => (
            <li
              key={a.nome}
              className="flex flex-wrap items-center gap-x-2 gap-y-1 border-b border-borda pb-2 last:border-0"
            >
              {a.politicoId ? (
                <Link
                  href={`/politicos/${a.politicoId}`}
                  className="font-medium text-acento underline underline-offset-2 hover:no-underline"
                >
                  {a.nome}
                </Link>
              ) : (
                <span className="font-medium text-texto">{a.nome}</span>
              )}
              {a.autorPrincipal && (
                <span className="rounded-padrao bg-acento-sutil px-1.5 py-0.5 text-xs text-acento">
                  Autoria principal
                </span>
              )}
              {!a.politicoId && (
                <span className="text-xs text-texto-tenue">
                  não é candidato em 2026
                </span>
              )}
            </li>
          ))}
        </ul>
      </section>
    </article>
  );
}
