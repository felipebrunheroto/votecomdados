/**
 * A apresentação de uma proposição, sem saber de onde o dado veio.
 *
 * <p>Existe porque a mesma tela é montada por dois caminhos: a rota
 * pré-renderizada em build time (legislatura corrente) e o fallback no
 * navegador de `not-found.tsx` (matéria anterior a 2026, que deixou de ser
 * pré-renderizada quando a base passou de 346 mil). Duplicar o JSX faria as
 * duas divergirem com o tempo -- e a versão do fallback, por ser a menos
 * vista, seria a que apodreceria.
 */
import Link from "next/link";
import type { ProposicaoDetalhe } from "@/lib/api/tipos";
import { formatarData, rotularCasa, rotularEsfera } from "@/lib/formato";
import { LinkFonteOficial } from "./LinkFonteOficial";

export function DetalheDaProposicao({ p }: { p: ProposicaoDetalhe }) {
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
