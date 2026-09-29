import Link from "next/link";
import type { EmendasDoMunicipio } from "@/lib/api/tipos";
import { formatarReais } from "@/lib/formato";
import { NotaDeExecucao } from "@/componentes/dominio/LacunaDeEmendas";
import { PeriodoDasEmendas } from "@/componentes/dominio/PeriodoDasEmendas";
import {
  ExplicacaoDaAusencia, LacunaNacional,
} from "@/componentes/dominio/LacunaNacional";

/**
 * A tela de uma cidade.
 *
 * <h2>Por que é puramente de apresentação</h2>
 *
 * Ela é renderizada em dois lugares: no HTML pré-gerado das cidades com
 * dado, e no navegador pelo fallback de `not-found.tsx` para as outras 5.096.
 * Se buscasse os dados sozinha, a primeira perderia o conteúdo no HTML — e
 * perder isso foi exatamente o defeito que manteve `/sobre/` quebrada por
 * semanas até 25/09/2026.
 */
export function VistaDoMunicipio({ dados }: { dados: EmendasDoMunicipio }) {
  return (
    <>
      <h1 className="text-2xl font-semibold tracking-tight">
        {dados.municipio} — {dados.uf}
      </h1>
      <p className="mt-1 text-sm text-texto-suave">
        Emendas parlamentares federais
      </p>
      {dados.temRegistro
        ? <ComRegistro dados={dados} />
        : <SemRegistro
            uf={dados.uf}
            nacional={dados.nacional}
            periodo={dados.resumo.periodo}
          />}
    </>
  );
}

function ComRegistro({ dados }: { dados: EmendasDoMunicipio }) {
  const { resumo, emendas } = dados;

  return (
    <>
      <div className="mt-6">
        <PeriodoDasEmendas periodo={resumo.periodo} />
      </div>

      <dl className="mt-2 flex flex-wrap gap-8">
        <div>
          {/*
            O número grande é DESEMBOLSO, não "pago". Santos tem pago R$ 0,00
            e recebeu R$ 600 mil por restos a pagar; destacar "pago" diria que
            a cidade não recebeu nada, e não são poucas nessa situação.
          */}
          <dd className="text-3xl font-semibold tracking-tight tabular-nums">
            {formatarReais(resumo.desembolso)}
          </dd>
          <dt className="text-sm text-texto-suave">recebidos, com origem identificada</dt>
        </div>
        <div>
          <dd className="text-3xl font-semibold tracking-tight tabular-nums">
            {dados.parlamentares}
          </dd>
          <dt className="text-sm text-texto-suave">
            {dados.parlamentares === 1 ? "parlamentar" : "parlamentares"}
          </dt>
        </div>
      </dl>

      {resumo.restoPago > 0 && resumo.pago === 0 && (
        <p className="mt-4 rounded border border-aviso-borda bg-aviso-fundo p-3 text-sm text-aviso-texto">
          <strong>O orçamento do ano registra R$ 0,00 pago para esta cidade.</strong>{" "}
          O dinheiro chegou por <strong>restos a pagar</strong> — orçamento de
          anos anteriores executado agora. Uma leitura que olhasse só a coluna
          &ldquo;pago&rdquo; diria que nada foi recebido.
        </p>
      )}

      <div className="mt-6 overflow-x-auto">
        <table className="w-full border-collapse text-sm">
          <thead>
            <tr className="border-b border-borda-forte text-left text-xs uppercase tracking-wide text-texto-tenue">
              <th className="px-2 py-2 font-semibold">Autoria</th>
              <th className="px-2 py-2 font-semibold">Ano</th>
              <th className="px-2 py-2 text-right font-semibold">Empenhado</th>
              <th className="px-2 py-2 text-right font-semibold">Desembolsado</th>
            </tr>
          </thead>
          <tbody>
            {emendas.map((e) => (
              <tr key={e.codigo} className="border-b border-borda last:border-0">
                <td className="px-2 py-2">
                  {e.politicoId ? (
                    <Link href={`/politicos/${e.politicoId}/`} className="text-acento hover:underline">
                      {e.autorNome}
                    </Link>
                  ) : (
                    e.autorNome
                  )}
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
                <td className="px-2 py-2 text-right tabular-nums">{formatarReais(e.empenhado)}</td>
                <td className="px-2 py-2 text-right tabular-nums">{formatarReais(e.desembolso)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <LacunaNacional nacional={dados.nacional} />

      <NotaDeExecucao resumo={resumo} />
    </>
  );
}

/**
 * A tela da MAIORIA dos municípios.
 *
 * Quantos exatamente varia com os anos carregados — eram 474 de 5.571 com um
 * ano, 1.596 com quatro —, e por isso o número vem da API e não daqui. Esta
 * não é a tela de exceção: precisa dizer com clareza o que a ausência
 * significa e, principalmente, <b>oferecer saída</b>, senão é um beco.
 */
function SemRegistro({
  uf, nacional, periodo,
}: {
  uf: string;
  nacional: EmendasDoMunicipio["nacional"];
  periodo: EmendasDoMunicipio["resumo"]["periodo"];
}) {
  return (
    <div className="mt-6 rounded border border-dashed border-borda-forte bg-fundo-sutil p-8 text-center">
      {/*
        O periodo importa MAIS aqui do que na tela cheia: sem ele, "nenhuma
        emenda" se le como "nunca", quando significa "nao no periodo que
        carregamos".
      */}
      <PeriodoDasEmendas periodo={periodo} />
      <h2 className="mt-2 text-lg font-semibold">
        Nenhuma emenda com esta cidade identificada na fonte
      </h2>
      <ExplicacaoDaAusencia nacional={nacional} />
      <p className="mt-4 text-sm">
        <Link href={`/?uf=${uf}`} className="text-acento hover:underline">
          Ver candidatos de {uf}
        </Link>
        {" · "}
        <Link href="/sobre/" className="text-acento hover:underline">
          Como lemos os dados de emendas
        </Link>
      </p>
    </div>
  );
}
