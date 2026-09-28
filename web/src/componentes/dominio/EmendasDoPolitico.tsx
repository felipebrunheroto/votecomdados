"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { listarEmendasDoPolitico } from "@/lib/api/cliente";
import type { PaginaDeEmendas } from "@/lib/api/tipos";
import { formatarReais } from "@/lib/formato";
import { LacunaDeEmendas, NotaDeExecucao } from "@/componentes/dominio/LacunaDeEmendas";

/**
 * Emendas de autoria do parlamentar.
 *
 * <h2>A seção só existe quando há dado</h2>
 *
 * Apenas 1.066 das 20.874 pessoas da base exerceram mandato federal — 5,1%.
 * Os outros 94,9% <b>nunca terão emenda</b>, e não por falta de coleta: quem
 * nunca exerceu mandato federal não indica emenda ao orçamento da União.
 *
 * Uma aba "Emendas" vazia em 19 de cada 20 perfis seria ruído, e pior:
 * sugeriria ausência de algo que deveria estar lá. Por isso o componente
 * devolve `null` — não um estado vazio.
 *
 * <h2>Carregado no cliente</h2>
 *
 * O perfil é pré-renderizado para a minoria com atuação legislativa, e as
 * emendas mudam ao longo do ano (empenhado em março, pago em outubro). Buscar
 * no cliente evita que o HTML congele um número que envelhece.
 */
export function EmendasDoPolitico({ politicoId }: { politicoId: string }) {
  const [dados, setDados] = useState<PaginaDeEmendas | null>(null);
  const [carregando, setCarregando] = useState(true);

  useEffect(() => {
    let vivo = true;
    listarEmendasDoPolitico(politicoId)
      .then((r) => { if (vivo) setDados(r); })
      .finally(() => { if (vivo) setCarregando(false); });
    return () => { vivo = false; };
  }, [politicoId]);

  // Nem "carregando", nem "nenhuma emenda": silêncio. Ver o javadoc acima.
  if (carregando || !dados || dados.resumo.linhas === 0) return null;

  const { resumo, data } = dados;
  const comCidade = data.filter((e) => e.localidadeTipo === "MUNICIPIO");

  return (
    <section aria-labelledby="titulo-emendas" className="mt-8">
      <h2 id="titulo-emendas" className="text-lg font-semibold tracking-tight">
        Emendas ao orçamento
      </h2>

      <dl className="mt-4 flex flex-wrap gap-8">
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
            {comCidade.length === 1 ? "emenda com cidade" : "emendas com cidade identificada"}
          </dt>
        </div>
      </dl>

      {comCidade.length > 0 && (
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
      )}

      <LacunaDeEmendas resumo={resumo} />
      <NotaDeExecucao resumo={resumo} />
    </section>
  );
}
