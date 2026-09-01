import Link from "next/link";
import type { Proposicao } from "@/lib/api/tipos";
import { formatarData, rotularCasa } from "@/lib/formato";
import { LinkFonteOficial } from "./LinkFonteOficial";

export function ListaProposicoes({ itens }: { itens: Proposicao[] }) {
  if (itens.length === 0) return null;

  return (
    <ul className="divide-y divide-borda">
      {itens.map((p) => (
        <li key={`${p.casa}-${p.id}`} className="py-4">
          <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
            <h3 className="font-semibold">
              <Link
                href={`/proposicoes/${p.id}`}
                className="text-texto underline decoration-borda-forte underline-offset-2 hover:decoration-acento"
              >
                {p.siglaTipo} {p.numero}/{p.ano}
              </Link>
            </h3>
            <span className="text-sm text-texto-suave">
              {rotularCasa(p.casa)} · {formatarData(p.dataApresentacao)}
            </span>
          </div>

          <p className="mt-1.5 text-texto-suave">{p.ementa}</p>

          {p.temas.length > 0 && (
            <ul className="mt-2 flex flex-wrap gap-1.5" aria-label="Temas">
              {p.temas.map((t) => (
                <li
                  key={t}
                  className="rounded-padrao bg-fundo-sutil px-2 py-0.5 text-xs text-texto-suave"
                >
                  {t}
                </li>
              ))}
            </ul>
          )}

          <div className="mt-2.5 flex flex-wrap items-center gap-x-4 gap-y-1">
            {p.situacaoAtual && (
              <span className="text-sm text-texto-tenue">{p.situacaoAtual}</span>
            )}
            <LinkFonteOficial
              href={p.urlTramitacao}
              descricaoAcessivel={`tramitação de ${p.siglaTipo} ${p.numero}/${p.ano}`}
            >
              Tramitação oficial
            </LinkFonteOficial>
            {p.urlInteiroTeor && (
              <LinkFonteOficial
                href={p.urlInteiroTeor}
                descricaoAcessivel={`inteiro teor de ${p.siglaTipo} ${p.numero}/${p.ano}`}
              >
                Inteiro teor
              </LinkFonteOficial>
            )}
          </div>
        </li>
      ))}
    </ul>
  );
}
