import Link from "next/link";
import type { PoliticoResumo } from "@/lib/api/tipos";
import { rotularCargo, rotularStatusCandidatura } from "@/lib/formato";

/**
 * Iniciais no lugar da foto — e não é fallback, é a única forma.
 *
 * Decisão de produto (01/09/2026): a plataforma não terá fotos de candidato.
 * `aria-hidden` porque o nome já vem escrito ao lado: anunciar as iniciais
 * seria repetir a mesma informação para quem usa leitor de tela.
 */
function Iniciais({ nome }: { nome: string }) {
  const iniciais = nome.split(" ").filter(Boolean).slice(0, 2).map((p) => p[0]).join("");
  return (
    <span
      aria-hidden="true"
      className="flex h-12 w-12 shrink-0 items-center justify-center rounded-full bg-fundo-sutil text-sm font-semibold text-texto-suave"
    >
      {iniciais.toUpperCase()}
    </span>
  );
}

export function CartaoCandidato({ politico }: { politico: PoliticoResumo }) {
  const nomeExibido = politico.nomeUrna ?? politico.nomeCivil;
  const registroIrregular = politico.statusCandidatura !== "DEFERIDO";

  return (
    <li>
      <Link
        href={`/politicos/${politico.id}`}
        className="flex gap-4 rounded-padrao border border-borda bg-superficie p-4 transition-colors hover:border-borda-forte hover:bg-fundo-sutil"
      >
        <Iniciais nome={nomeExibido} />

        <div className="min-w-0 flex-1">
          <p className="font-semibold text-texto">{nomeExibido}</p>
          {politico.nomeUrna && politico.nomeUrna !== politico.nomeCivil && (
            <p className="text-sm text-texto-tenue">{politico.nomeCivil}</p>
          )}
          <p className="mt-0.5 text-sm text-texto-suave">
            {rotularCargo(politico.cargo2026)} · {politico.uf} · {politico.partidoSigla}
          </p>

          <p className="mt-1.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs">
            {registroIrregular && (
              <span className="rounded-padrao bg-aviso-fundo px-1.5 py-0.5 font-medium text-aviso-texto">
                {rotularStatusCandidatura(politico.statusCandidatura)}
              </span>
            )}
            <span className="text-texto-tenue">
              {politico.possuiAtuacaoLegislativa
                ? "Tem mandato legislativo anterior"
                : "Sem mandato legislativo anterior"}
            </span>
          </p>
        </div>
      </Link>
    </li>
  );
}
