import Link from "next/link";

/**
 * Rodapé do site.
 *
 * <h2>Uma linha para os dados abertos, não duas (10/09/2026)</h2>
 *
 * O rodapé tinha dois itens: a página que explica o pacote e um link direto
 * ao pacote. Ficou só o primeiro.
 *
 * O que o link preserva é o que importa: a plataforma faz uma afirmação
 * impossível de conferir de fora — "este deputado é esta candidata" — e o
 * pacote de dados abertos é o que a torna auditável. Estar em toda tela é o
 * que garante que quem quiser conferir encontre; depender de a pessoa chegar
 * a um perfil não garantiria.
 *
 * O que se perdeu é o atalho de um clique até o arquivo. Quem quer os CSVs
 * passa pela página, que agora é o único caminho — e é ela que explica os
 * cinco avisos que o pacote exige antes de ser usado, o que torna a parada
 * intencional em vez de atrito.
 *
 * <h2>Se um link direto ao pacote voltar: `<a>`, nunca `<Link>`</h2>
 *
 * `/dados-abertos/latest/` **não é rota do Next** — são arquivos que o worker
 * de ingestão publica na mesma CDN. Um `<Link>` tentaria navegação
 * client-side para uma rota que o roteador não conhece e quebraria a
 * navegação. A página `/dados-abertos` (essa sim, rota) usa `<Link>`.
 */
export function Rodape() {
  return (
    <footer className="mt-16 border-t border-borda">
      <div className="mx-auto max-w-4xl space-y-6 px-4 py-8 text-sm text-texto-suave">
        <nav aria-label="Transparência da plataforma">
          <h2 className="font-medium text-texto">Confira o nosso trabalho</h2>
          <ul className="mt-2 space-y-1.5">
            <li>
              <Link href="/sobre" className="underline underline-offset-2 hover:text-texto">
                Sobre os dados
              </Link>
              {" — "}de onde vem cada informação, o que cobrimos e o que não.
            </li>
            <li>
              <Link
                href="/dados-abertos"
                className="underline underline-offset-2 hover:text-texto"
              >
                Dados abertos
              </Link>
              {" — "}a base inteira para baixar, conferir e reusar.
            </li>
          </ul>
        </nav>

        <div className="space-y-2 border-t border-borda pt-6">
          <p>
            Dados públicos do TSE, da Câmara dos Deputados, do Senado Federal e
            da Assembleia Legislativa de São Paulo. Cada matéria e votação traz
            o link para a fonte oficial.
          </p>
          <p className="text-texto-tenue">
            A plataforma não classifica nem ranqueia candidatos.
          </p>
        </div>
      </div>
    </footer>
  );
}
