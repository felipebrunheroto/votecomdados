"use client";

import { useEffect, useState } from "react";
import { obterStatusFontes } from "@/lib/api/cliente";
import type { StatusFontes } from "@/lib/api/tipos";
import { formatarDataLonga, rotularCasa } from "@/lib/formato";

const NOME_FONTE: Record<string, string> = {
  CAMARA: "Câmara dos Deputados",
  SENADO: "Senado Federal",
  ALESP: "Assembleia Legislativa de São Paulo",
  TSE: "Tribunal Superior Eleitoral",
};

/**
 * Frescor por fonte, buscado em TEMPO DE EXECUÇÃO.
 *
 * O HTML desta página é estático e pode ter sido gerado dias antes; exibir a
 * data do build afirmaria uma atualidade que talvez não exista. Buscar no
 * navegador é o que faz o indicador refletir o pipeline de verdade
 * (ver ARQUITETURA.md § 8).
 */
export function FrescorDosDados() {
  const [dados, setDados] = useState<StatusFontes | null>(null);
  const [erro, setErro] = useState(false);

  useEffect(() => {
    let cancelado = false;
    obterStatusFontes()
      .then((d) => { if (!cancelado) setDados(d); })
      .catch(() => { if (!cancelado) setErro(true); });
    return () => { cancelado = true; };
  }, []);

  if (erro) {
    return (
      <p className="text-sm text-texto-suave">
        Não foi possível consultar a data da última atualização.
      </p>
    );
  }

  if (!dados) {
    return <p className="text-sm text-texto-tenue">Consultando as fontes…</p>;
  }

  return (
    <ul className="divide-y divide-borda">
      {dados.fontes.map((f) => {
        const desatualizada = f.status === "FALHA";
        return (
          <li key={f.fonte} className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 py-2.5">
            <span className="font-medium text-texto">
              {NOME_FONTE[f.fonte] ?? rotularCasa(f.fonte)}
            </span>
            <span className="text-sm text-texto-suave">
              Atualizado em {formatarDataLonga(f.ultimaAtualizacao)}
              {/* Quando a última execução falhou, dizer só a data sugeriria que
                  o dado está em dia. O aviso é o que impede essa leitura. */}
              {desatualizada && (
                <span className="ml-2 rounded-padrao bg-aviso-fundo px-1.5 py-0.5 text-xs text-aviso-texto">
                  a sincronização seguinte falhou — o dado pode estar defasado
                </span>
              )}
            </span>
          </li>
        );
      })}
    </ul>
  );
}
