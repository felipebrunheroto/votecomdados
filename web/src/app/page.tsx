import { Suspense } from "react";
import { BuscaDeCandidatos } from "@/componentes/dominio/BuscaDeCandidatos";
import { BuscaDeMunicipios } from "@/componentes/dominio/BuscaDeMunicipios";
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

      {/*
        Segunda porta de entrada, abaixo da primeira.

        As duas perguntas que a plataforma responde são diferentes e igualmente
        legítimas: "o que este candidato fez" e "o que veio para a minha
        cidade". A segunda só existia atrás de um link no cabeçalho, e no
        discovery foi justamente ela que argumentei ser o enquadramento mais
        útil ao eleitor -- a pergunta sobre o próprio município, onde não há
        ranking a fabricar.

        A separação visual importa: são dois campos de busca na mesma tela, e
        sem um título e uma borda que os separe a pessoa não sabe qual usar.
      */}
      <section aria-labelledby="titulo-emendas-municipio" className="border-t border-borda pt-8">
        <h2
          id="titulo-emendas-municipio"
          className="text-xl font-semibold tracking-tight text-texto"
        >
          Emendas por município
        </h2>
        <p className="mt-2 max-w-prose text-texto-suave">
          Quanto de emenda parlamentar federal a fonte registra com destino à
          sua cidade — e quanto do dinheiro não tem município declarado.
        </p>
        <BuscaDeMunicipios />
      </section>
    </div>
  );
}
