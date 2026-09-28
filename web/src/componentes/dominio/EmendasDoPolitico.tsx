import Link from "next/link";
import type { PaginaDeEmendas } from "@/lib/api/tipos";
import { formatarReais } from "@/lib/formato";
import { LacunaDeEmendas, NotaDeExecucao } from "@/componentes/dominio/LacunaDeEmendas";
import { PeriodoDasEmendas } from "@/componentes/dominio/PeriodoDasEmendas";

/**
 * O painel da aba "Emendas".
 *
 * <h2>Puramente de apresentação</h2>
 *
 * Quem busca é {@link AbasDeAtuacao}, porque a aba só deve EXISTIR quando há
 * emenda — e isso precisa ser sabido antes de montar a lista de abas. Buscar
 * aqui dentro obrigaria a aba a aparecer primeiro e sumir depois, ou a exibir
 * um vazio que não se aplica a 94,9% da base.
 *
 * <h2>A ordem dos blocos não é estética</h2>
 *
 * A lacuna vem logo abaixo da cifra e ANTES da tabela. Posta depois, o leitor
 * já formou a conclusão — vê três linhas, soma de olho, e conclui que aquilo
 * é o total. A ressalva precisa chegar junto com o número que ela qualifica,
 * não como nota de rodapé depois da evidência.
 */
export function EmendasDoPolitico({ dados }: { dados: PaginaDeEmendas }) {
  const { resumo, data } = dados;
  const comCidade = data.filter((e) => e.localidadeTipo === "MUNICIPIO");

  return (
    <div>
      {/* Antes da cifra: o leitor precisa saber de quando e o numero ANTES
          de le-lo, nao depois. */}
      <PeriodoDasEmendas periodo={resumo.periodo} />

      <dl className="mt-2 flex flex-wrap gap-8">
        <div>
          <dd className="text-2xl font-semibold tracking-tight tabular-nums">
            {formatarReais(resumo.desembolso)}
          </dd>
          <dt className="text-sm text-texto-suave">desembolsados no total</dt>
        </div>
        <div>
          <dd className="text-2xl font-semibold tracking-tight tabular-nums text-texto-tenue">
            {comCidade.length}
          </dd>
          <dt className="text-sm text-texto-suave">
            {comCidade.length === 1
              ? "emenda com cidade identificada"
              : "emendas com cidade identificada"}
          </dt>
        </div>
      </dl>

      {/* Antes da tabela, de propósito. Ver o javadoc acima. */}
      <LacunaDeEmendas resumo={resumo} />

      {comCidade.length > 0 ? (
        <div className="mt-4 overflow-x-auto">
          <table className="w-full border-collapse text-sm">
            <thead>
              <tr className="border-b border-borda-forte text-left text-xs uppercase tracking-wide text-texto-tenue">
                <th className="px-2 py-2 font-semibold">Município</th>
                <th className="px-2 py-2 font-semibold">Ano</th>
                <th className="px-2 py-2 text-right font-semibold">Empenhado</th>
                <th className="px-2 py-2 text-right font-semibold">Desembolsado</th>
              </tr>
            </thead>
            <tbody>
              {comCidade.map((e) => (
                <tr key={e.codigo} className="border-b border-borda last:border-0">
                  <td className="px-2 py-2">
                    <Link
                      href={`/municipios/${e.uf}/${encodeURIComponent(e.municipio ?? "")}/`}
                      className="text-acento hover:underline"
                    >
                      {e.municipio} — {e.uf}
                    </Link>
                    {/*
                      Autoria transferida: 1,7% das linhas e R$ 473 mi em 2025.
                      Exibir só quem a detém hoje esconde metade da história.
                    */}
                    {e.autorOrigemNome && (
                      <span className="block text-xs text-texto-tenue">
                        recebida de {e.autorOrigemNome}
                      </span>
                    )}
                  </td>
                  <td className="px-2 py-2 tabular-nums">{e.ano}</td>
                  <td className="px-2 py-2 text-right tabular-nums">
                    {formatarReais(e.empenhado)}
                  </td>
                  <td className="px-2 py-2 text-right tabular-nums">
                    {formatarReais(e.desembolso)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <p className="mt-4 text-sm text-texto-suave">
          Nenhuma das emendas deste parlamentar tem município identificado na
          fonte — todas foram registradas como &ldquo;Múltiplo&rdquo;, por
          estado, ou de alcance nacional.
        </p>
      )}

      <NotaDeExecucao resumo={resumo} />
    </div>
  );
}
