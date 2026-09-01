"use client";

import { useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { listarPoliticos } from "@/lib/api/cliente";
import type { Cargo, Pagina, PoliticoResumo } from "@/lib/api/tipos";
import { formatarNumero } from "@/lib/formato";
import { CartaoCandidato } from "./CartaoCandidato";
import { FiltroBusca } from "./FiltroBusca";
import { Carregando, Erro, Vazio } from "../ui/Estados";

export function BuscaDeCandidatos() {
  const params = useSearchParams();
  const [resultado, setResultado] = useState<Pagina<PoliticoResumo> | null>(null);
  const [estado, setEstado] = useState<"carregando" | "pronto" | "erro">("carregando");
  const [tentativa, setTentativa] = useState(0);

  const q = params.get("q") ?? undefined;
  const cargo = (params.get("cargo") as Cargo | null) ?? undefined;
  const uf = params.get("uf") ?? undefined;
  const comAtuacao = params.get("comAtuacao") === "true" || undefined;

  // Reset ao trocar de filtro acontece DURANTE a renderização: `setState`
  // síncrono dentro de um efeito provoca uma segunda renderização à toa e é
  // erro no React 19.
  const chave = `${q ?? ""}|${cargo ?? ""}|${uf ?? ""}|${comAtuacao ?? ""}`;
  const [chaveAtual, setChaveAtual] = useState(chave);
  if (chave !== chaveAtual) {
    setChaveAtual(chave);
    setEstado("carregando");
  }

  useEffect(() => {
    let cancelado = false;

    listarPoliticos({ q, cargo, uf, comAtuacao })
      .then((r) => {
        if (cancelado) return;
        setResultado(r);
        setEstado("pronto");
      })
      .catch(() => {
        if (!cancelado) setEstado("erro");
      });

    return () => { cancelado = true; };
  }, [q, cargo, uf, comAtuacao, tentativa]);

  return (
    <div className="space-y-6">
      <FiltroBusca />

      {/* Resultado anunciado a leitores de tela: sem isso, quem não vê a tela
          digita na busca e não recebe retorno algum. */}
      <p aria-live="polite" className="text-sm text-texto-suave">
        {estado === "pronto" && resultado
          ? `${formatarNumero(resultado.pagination.total)} ${
              resultado.pagination.total === 1
                ? "candidato encontrado"
                : "candidatos encontrados"
            }`
          : ""}
      </p>

      {estado === "carregando" && <Carregando rotulo="Carregando candidatos" />}
      {estado === "erro" && <Erro
          aoTentarNovamente={() => {
            setEstado("carregando");
            setTentativa((n) => n + 1);
          }}
        />}

      {estado === "pronto" && resultado && resultado.data.length === 0 && (
        <Vazio
          titulo="Nenhum candidato para estes filtros"
          descricao="Verifique a grafia do nome ou remova algum filtro. A plataforma cobre apenas quem tem registro de candidatura na eleição de 2026."
        />
      )}

      {estado === "pronto" && resultado && resultado.data.length > 0 && (
        <ul className="space-y-3">
          {resultado.data.map((p) => (
            <CartaoCandidato key={p.id} politico={p} />
          ))}
        </ul>
      )}
    </div>
  );
}
