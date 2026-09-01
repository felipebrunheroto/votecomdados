import Link from "next/link";

/**
 * Rodapé do site.
 *
 * <h2>Por que o link para os dados abertos vive aqui</h2>
 *
 * A plataforma faz uma afirmação que ninguém consegue conferir de fora — "este
 * deputado é esta candidata" —, e o pacote de dados abertos é o que torna essa
 * afirmação auditável. Ele era gerado e publicado sem que nenhuma tela
 * apontasse para ele: dado publicado que ninguém encontra não foi publicado.
 *
 * O rodapé é o lugar certo porque a auditoria não é tarefa de uma página só; é
 * a promessa do produto inteiro, e precisa estar acessível de qualquer tela.
 *
 * <h2>`<a>` e não `<Link>` no pacote — e a diferença não é estilo</h2>
 *
 * `/dados-abertos/latest/` **não é rota do Next**: são arquivos que o worker de
 * ingestão publica na mesma CDN. Um `<Link>` tentaria navegação client-side
 * para uma rota que o roteador não conhece e quebraria a navegação. A página
 * `/dados-abertos` (essa sim, rota) explica o pacote; o `<a>` leva ao pacote.
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
            <li>
              {/* Arquivo estático publicado pela ingestão, não rota do Next. */}
              <a
                href="/dados-abertos/latest/"
                className="underline underline-offset-2 hover:text-texto"
              >
                Baixar o pacote mais recente
              </a>
              {" — "}CSV e metodologia, licença CC BY 4.0.
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
