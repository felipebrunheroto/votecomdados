import { Suspense } from "react";
import { BuscaDeCandidatos } from "@/componentes/dominio/BuscaDeCandidatos";
import { Carregando } from "@/componentes/ui/Estados";

export default function Home() {
  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight text-texto">
          O que os candidatos de 2026 fizeram no Legislativo
        </h1>
        <p className="mt-2 max-w-prose text-texto-suave">
          Proposições apresentadas e votos registrados, extraídos de dados
          abertos oficiais. Cada informação traz o link para a fonte, para você
          conferir por conta própria.
        </p>
      </div>

      {/* useSearchParams exige limite de Suspense para não bloquear a
          pré-renderização estática da página inteira. */}
      <Suspense fallback={<Carregando rotulo="Carregando busca" />}>
        <BuscaDeCandidatos />
      </Suspense>
    </div>
  );
}
