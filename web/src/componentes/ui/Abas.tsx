"use client";

import { useId, useRef, useState } from "react";

/**
 * Abas com o padrão ARIA completo, feitas à mão em vez de trazer uma
 * dependência: navegação por seta, Home/End, `aria-selected`, e apenas a aba
 * ativa no fluxo de tabulação (roving tabindex).
 *
 * O painel recebe foco programático na troca para que o leitor de tela anuncie
 * o novo conteúdo — sem isso o usuário troca de aba e não percebe a mudança.
 */
export interface Aba {
  id: string;
  rotulo: string;
  contador?: number;
  conteudo: React.ReactNode;
}

export function Abas({ abas, rotuloLista }: { abas: Aba[]; rotuloLista: string }) {
  const [ativa, setAtiva] = useState(0);
  const baseId = useId();
  const refs = useRef<(HTMLButtonElement | null)[]>([]);

  function aoTeclar(evento: React.KeyboardEvent, indice: number) {
    const ultimo = abas.length - 1;
    let destino: number | null = null;

    if (evento.key === "ArrowRight") destino = indice === ultimo ? 0 : indice + 1;
    else if (evento.key === "ArrowLeft") destino = indice === 0 ? ultimo : indice - 1;
    else if (evento.key === "Home") destino = 0;
    else if (evento.key === "End") destino = ultimo;

    if (destino !== null) {
      evento.preventDefault();
      setAtiva(destino);
      refs.current[destino]?.focus();
    }
  }

  return (
    <div>
      <div
        role="tablist"
        aria-label={rotuloLista}
        className="flex gap-1 border-b border-borda"
      >
        {abas.map((aba, i) => {
          const selecionada = i === ativa;
          return (
            <button
              key={aba.id}
              ref={(el) => { refs.current[i] = el; }}
              role="tab"
              id={`${baseId}-aba-${aba.id}`}
              aria-selected={selecionada}
              aria-controls={`${baseId}-painel-${aba.id}`}
              tabIndex={selecionada ? 0 : -1}
              onClick={() => setAtiva(i)}
              onKeyDown={(e) => aoTeclar(e, i)}
              className={`-mb-px border-b-2 px-4 py-2.5 text-sm font-medium transition-colors ${
                selecionada
                  ? "border-acento text-acento"
                  : "border-transparent text-texto-suave hover:text-texto"
              }`}
            >
              {aba.rotulo}
              {typeof aba.contador === "number" && (
                <span className="ml-1.5 text-texto-tenue">({aba.contador})</span>
              )}
            </button>
          );
        })}
      </div>

      {abas.map((aba, i) => (
        <div
          key={aba.id}
          role="tabpanel"
          id={`${baseId}-painel-${aba.id}`}
          aria-labelledby={`${baseId}-aba-${aba.id}`}
          hidden={i !== ativa}
          tabIndex={0}
          className="pt-4 focus:outline-none"
        >
          {i === ativa && aba.conteudo}
        </div>
      ))}
    </div>
  );
}
