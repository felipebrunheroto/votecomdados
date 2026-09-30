import type { Metadata } from "next";
import Link from "next/link";
import { Rodape } from "@/componentes/dominio/Rodape";
import { SITE } from "@/lib/site";
import "./globals.css";

export const metadata: Metadata = {
  // Sem isto o Next resolve URL de Open Graph e canônica como caminho
  // relativo, que nenhum agregador aceita. Passou a existir junto com o
  // sitemap (29/09/2026), pelo mesmo motivo: os dois precisam de absoluta.
  metadataBase: new URL(SITE),
  title: {
    default: "VoteComDados — atuação dos candidatos de 2026",
    template: "%s · VoteComDados",
  },
  description:
    "Consulte proposições apresentadas e votos registrados pelos candidatos da eleição de 2026, sempre com link para a fonte oficial.",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="pt-BR">
      <body className="min-h-screen bg-fundo antialiased">
        {/* Primeiro elemento focável da página: quem navega por teclado não
            deve percorrer o cabeçalho inteiro a cada troca de rota. */}
        <a
          href="#conteudo"
          className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50 focus:rounded-padrao focus:bg-acento focus:px-4 focus:py-2 focus:text-acento-contraste"
        >
          Pular para o conteúdo
        </a>

        {/*
          Sem navegação no cabeçalho, de propósito.

          As duas buscas — candidato e município — vivem na própria home, e
          "Sobre os dados" e "Dados abertos" estão no rodapé. Repetir os
          mesmos destinos em cima e embaixo não acrescenta caminho; só ocupa
          a faixa mais visível da tela com o que já está a um rolar de
          distância.

          A marca continua sendo link para a home: é o caminho de volta, e
          esse não está em nenhum outro lugar.
        */}
        <header className="border-b border-borda">
          <div className="mx-auto max-w-4xl px-4 py-4">
            <Link href="/" className="font-semibold tracking-tight text-texto">
              VoteComDados
            </Link>
          </div>
        </header>

        <main id="conteudo" className="mx-auto max-w-4xl px-4 py-8">
          {children}
        </main>

        <Rodape />
      </body>
    </html>
  );
}
