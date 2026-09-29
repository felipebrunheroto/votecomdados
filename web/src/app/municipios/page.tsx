import type { Metadata } from "next";
import { BuscaDeMunicipios } from "@/componentes/dominio/BuscaDeMunicipios";

export const metadata: Metadata = {
  title: "Emendas por município",
  description:
    "Procure sua cidade e veja quais emendas parlamentares federais a fonte "
    + "registra com destino a ela — e quanto do dinheiro não tem município declarado.",
};

/**
 * A porta de entrada que faltava.
 *
 * <p>As 474 páginas de cidade existiam sem nenhum caminho até elas: só por
 * URL digitada, ou clicando na tabela de emendas de um parlamentar — ou seja,
 * era preciso já saber quem mandou dinheiro para lá.
 *
 * <p>Isso invertia o enquadramento que o próprio discovery recomendou. A
 * pergunta útil ao eleitor é "o que veio para a MINHA cidade", e ela só
 * existia pelo caminho do candidato, que é o enquadramento com risco de virar
 * ranking.
 */
export default function PaginaDeMunicipios() {
  return (
    <main className="mx-auto max-w-3xl px-4 py-8">
      <h1 className="text-2xl font-semibold tracking-tight">Emendas por município</h1>
      <p className="mt-2 max-w-prose text-sm text-texto-suave">
        Procure sua cidade para ver quais emendas parlamentares federais a
        fonte registra com destino a ela.
      </p>
      <BuscaDeMunicipios />
    </main>
  );
}
