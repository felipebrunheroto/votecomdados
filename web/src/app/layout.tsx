import type { Metadata } from "next";
import Link from "next/link";
import { Rodape } from "@/componentes/dominio/Rodape";
import "./globals.css";

export const metadata: Metadata = {
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

        <header className="border-b border-borda">
          <div className="mx-auto flex max-w-4xl items-center justify-between px-4 py-4">
            <Link href="/" className="font-semibold tracking-tight text-texto">
              VoteComDados
            </Link>
            <nav aria-label="Principal">
              <Link href="/sobre" className="text-sm text-texto-suave hover:text-texto">
                Sobre os dados
              </Link>
            </nav>
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
