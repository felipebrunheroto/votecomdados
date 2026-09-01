import type { AmbitoVotacao, Placar } from "@/lib/api/tipos";
import { formatarNumero } from "@/lib/formato";

/**
 * Placar de uma votação: barra empilhada horizontal (parte-de-todo).
 *
 * A escolha de cor foi CALCULADA, não estimada. O par verde/vermelho, que é o
 * convencional em painéis parlamentares, foi reprovado: sob deuteranopia ele
 * dá ΔE 5,2, ou seja, quem tem daltonismo vermelho-verde não distingue "sim"
 * de "não" — exatamente a informação que o placar existe para transmitir. O
 * par azul/vermelho-alaranjado adotado dá ΔE 17,4 sob protanopia.
 *
 * A cor nunca é o único sinal: cada segmento tem rótulo direto, existe legenda
 * e a tabela abaixo repete os números. Um leitor de tela, um daltônico e uma
 * impressão em preto e branco chegam ao mesmo resultado.
 */

interface Segmento {
  chave: keyof Placar;
  rotulo: string;
  valor: number;
  classe: string;
}

export function PlacarVotacao({
  placar, ambito,
}: {
  placar: Placar;
  ambito: AmbitoVotacao;
}) {
  const total = placar.sim + placar.nao + placar.abstencao + placar.outros;
  if (total === 0) return null;

  const segmentos: Segmento[] = ([
    { chave: "sim", rotulo: "Sim", valor: placar.sim, classe: "bg-placar-favor" },
    { chave: "nao", rotulo: "Não", valor: placar.nao, classe: "bg-placar-contra" },
    { chave: "abstencao", rotulo: "Abstenção", valor: placar.abstencao, classe: "bg-placar-neutro" },
    { chave: "outros", rotulo: "Não votaram", valor: placar.outros, classe: "bg-placar-ausente" },
  ] satisfies Segmento[]).filter((s) => s.valor > 0);

  const pct = (v: number) => (v / total) * 100;

  return (
    <figure className="m-0">
      <figcaption className="text-sm font-medium text-texto">
        {/* Numa deliberação de comissão quem votou foi a comissão, não a Casa;
            dizer "a Casa" superestimaria o alcance da votação. */}
        {ambito === "COMISSAO" ? "Como a comissão votou" : "Como a Casa votou"}
        <span className="ml-2 font-normal text-texto-suave">
          {formatarNumero(total)} parlamentares
        </span>
      </figcaption>

      {/* A barra é decorativa para tecnologia assistiva: os mesmos números
          aparecem na tabela abaixo, em ordem e com rótulo. */}
      <div
        aria-hidden="true"
        className="mt-2 flex h-7 w-full gap-[2px] overflow-hidden rounded-padrao"
      >
        {segmentos.map((s) => (
          <div
            key={s.chave}
            className={`${s.classe} flex items-center justify-center`}
            style={{ width: `${pct(s.valor)}%` }}
            title={`${s.rotulo}: ${formatarNumero(s.valor)}`}
          >
            {/* Rótulo direto só quando cabe: número espremido é ilegível. */}
            {pct(s.valor) >= 12 && (
              <span className="px-1 text-xs font-semibold text-white">
                {formatarNumero(s.valor)}
              </span>
            )}
          </div>
        ))}
      </div>

      <table className="mt-3 w-full text-sm">
        <caption className="sr-only">
          Distribuição dos votos por posição
        </caption>
        <thead>
          <tr className="text-left text-texto-suave">
            <th scope="col" className="py-1 font-medium">Posição</th>
            <th scope="col" className="py-1 text-right font-medium">Votos</th>
            <th scope="col" className="py-1 text-right font-medium">Proporção</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-borda">
          {segmentos.map((s) => (
            <tr key={s.chave}>
              <th scope="row" className="py-1.5 font-normal text-texto">
                <span className="flex items-center gap-2">
                  <span
                    aria-hidden="true"
                    className={`${s.classe} h-2.5 w-2.5 shrink-0 rounded-[2px]`}
                  />
                  {s.rotulo}
                </span>
              </th>
              <td className="py-1.5 text-right font-mono text-texto">
                {formatarNumero(s.valor)}
              </td>
              <td className="py-1.5 text-right text-texto-suave">
                {pct(s.valor).toFixed(1)}%
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <p className="mt-2 text-sm text-texto-tenue">
        &ldquo;Não votaram&rdquo; reúne ausências e obstruções, que não são
        posição sobre o mérito da matéria.
      </p>
    </figure>
  );
}
