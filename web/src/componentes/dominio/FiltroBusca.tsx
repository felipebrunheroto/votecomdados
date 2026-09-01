"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { useCallback } from "react";
import type { Cargo } from "@/lib/api/tipos";
import { rotularCargo } from "@/lib/formato";

/**
 * Busca e filtros. O estado vive na URL, não em `useState`: o resultado fica
 * compartilhável, sobrevive a refresh e o botão voltar do navegador funciona
 * como o usuário espera (ver docs/FRONTEND.md § 2).
 */

const CARGOS: Cargo[] = [
  "PRESIDENTE", "GOVERNADOR", "SENADOR",
  "DEPUTADO_FEDERAL", "DEPUTADO_ESTADUAL", "DEPUTADO_DISTRITAL",
];

const UFS = [
  "AC","AL","AM","AP","BA","CE","DF","ES","GO","MA","MG","MS","MT","PA","PB",
  "PE","PI","PR","RJ","RN","RO","RR","RS","SC","SE","SP","TO",
];

export function FiltroBusca() {
  const router = useRouter();
  const params = useSearchParams();

  const atualizar = useCallback(
    (chave: string, valor: string) => {
      const novos = new URLSearchParams(params.toString());
      if (valor) novos.set(chave, valor);
      else novos.delete(chave);
      novos.delete("page"); // trocar filtro sempre volta para a primeira página
      router.replace(novos.toString() ? `/?${novos}` : "/", { scroll: false });
    },
    [params, router],
  );

  const classeCampo =
    "rounded-padrao border border-borda bg-superficie px-3 py-2 text-texto";

  return (
    <form
      role="search"
      onSubmit={(e) => e.preventDefault()}
      className="space-y-3"
    >
      <div>
        <label htmlFor="busca-nome" className="block text-sm font-medium text-texto">
          Buscar candidato
        </label>
        <input
          id="busca-nome"
          type="search"
          defaultValue={params.get("q") ?? ""}
          onChange={(e) => atualizar("q", e.target.value)}
          placeholder="Nome civil ou nome de urna"
          maxLength={100}
          className={`${classeCampo} mt-1 w-full`}
        />
      </div>

      <div className="flex flex-wrap gap-3">
        <div>
          <label htmlFor="filtro-cargo" className="block text-sm font-medium text-texto">
            Cargo em 2026
          </label>
          <select
            id="filtro-cargo"
            defaultValue={params.get("cargo") ?? ""}
            onChange={(e) => atualizar("cargo", e.target.value)}
            className={`${classeCampo} mt-1`}
          >
            <option value="">Todos</option>
            {CARGOS.map((c) => (
              <option key={c} value={c}>{rotularCargo(c)}</option>
            ))}
          </select>
        </div>

        <div>
          <label htmlFor="filtro-uf" className="block text-sm font-medium text-texto">
            UF
          </label>
          <select
            id="filtro-uf"
            defaultValue={params.get("uf") ?? ""}
            onChange={(e) => atualizar("uf", e.target.value)}
            className={`${classeCampo} mt-1`}
          >
            <option value="">Todas</option>
            {UFS.map((uf) => (
              <option key={uf} value={uf}>{uf}</option>
            ))}
          </select>
        </div>

        <div className="flex items-end">
          <label className="flex cursor-pointer items-center gap-2 py-2 text-sm text-texto">
            <input
              type="checkbox"
              defaultChecked={params.get("comAtuacao") === "true"}
              onChange={(e) => atualizar("comAtuacao", e.target.checked ? "true" : "")}
              className="h-4 w-4"
            />
            Somente com mandato anterior
          </label>
        </div>
      </div>
    </form>
  );
}
