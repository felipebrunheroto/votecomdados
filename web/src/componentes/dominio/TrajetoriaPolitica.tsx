import type { Candidatura } from "@/lib/api/tipos";
import { rotularCargo, rotularEsfera, rotularStatusCandidatura } from "@/lib/formato";

/**
 * Linha do tempo das disputas eleitorais, dos três níveis.
 *
 * Vem só do TSE, que é fonte uniforme para municipal, estadual e federal —
 * por isso a trajetória é completa mesmo quando a atuação legislativa não é.
 * Essa diferença é justamente o que o AvisoDeCobertura explica.
 */
export function TrajetoriaPolitica({ trajetoria }: { trajetoria: Candidatura[] }) {
  return (
    <section aria-labelledby="titulo-trajetoria">
      <h2 id="titulo-trajetoria" className="text-lg font-semibold text-texto">
        Trajetória eleitoral
      </h2>
      <p className="mt-1 text-sm text-texto-suave">
        Todas as candidaturas registradas no TSE, incluindo cargos municipais e
        estaduais.
      </p>

      <ol className="mt-4 space-y-0">
        {trajetoria.map((c, i) => {
          const eleito = c.eleito === true;
          const emDisputa = c.eleito === null;
          return (
            <li
              key={`${c.anoEleicao}-${c.cargo}-${c.uf}-${i}`}
              className="relative flex gap-4 pb-5 last:pb-0"
            >
              {/* Linha vertical conectando os pontos, exceto no último item. */}
              {i < trajetoria.length - 1 && (
                <span
                  aria-hidden="true"
                  className="absolute left-[7px] top-4 h-full w-px bg-borda"
                />
              )}
              <span
                aria-hidden="true"
                className={`relative mt-1.5 h-3.5 w-3.5 shrink-0 rounded-full border-2 ${
                  eleito
                    ? "border-acento bg-acento"
                    : "border-borda-forte bg-superficie"
                }`}
              />
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-baseline gap-x-2">
                  <span className="font-mono text-sm font-semibold text-texto">
                    {c.anoEleicao}
                  </span>
                  <span className="font-medium text-texto">{rotularCargo(c.cargo)}</span>
                  <span className="text-sm text-texto-suave">
                    {c.municipio ? `${c.municipio} · ${c.uf}` : c.uf} · {c.partidoSigla}
                  </span>
                </div>
                <p className="mt-0.5 text-sm text-texto-suave">
                  <span className="rounded-padrao bg-fundo-sutil px-1.5 py-0.5 text-xs">
                    {rotularEsfera(c.esfera)}
                  </span>{" "}
                  {emDisputa
                    ? rotularStatusCandidatura(c.status)
                    : eleito
                      ? "Eleito"
                      : "Não eleito"}
                  {!emDisputa && c.status !== "DEFERIDO"
                    ? ` · ${rotularStatusCandidatura(c.status)}`
                    : ""}
                </p>
              </div>
            </li>
          );
        })}
      </ol>
    </section>
  );
}
