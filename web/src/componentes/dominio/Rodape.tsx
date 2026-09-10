import Link from "next/link";

/**
 * Rodapé do site.
 *
 * <h2>Os links para dados abertos saíram daqui (10/09/2026)</h2>
 *
 * Decisão de produto: o rodapé ficou só com "Sobre os dados".
 *
 * O pacote continua sendo gerado, publicado e servido — o que mudou foi a
 * divulgação nesta tela. Quem chega nele hoje vem do perfil do candidato, que
 * linka `/dados-abertos`; a página segue explicando o pacote, e o
 * `ManifestoDoPacote` continua lendo `/dados-abertos/latest/manifesto.json`.
 *
 * Vale registrar o que se perdeu, porque foi uma escolha e não um descuido: o
 * argumento original era que a plataforma faz uma afirmação impossível de
 * conferir de fora — "este deputado é esta candidata" — e o pacote é o que a
 * torna auditável. Estar em toda tela era a forma de garantir que quem quisesse
 * conferir encontrasse. Agora depende de a pessoa chegar a um perfil.
 *
 * <h2>Se um link para o pacote voltar: `<a>`, nunca `<Link>`</h2>
 *
 * `/dados-abertos/latest/` **não é rota do Next** — são arquivos que o worker
 * de ingestão publica na mesma CDN. Um `<Link>` tentaria navegação
 * client-side para uma rota que o roteador não conhece e quebraria a
 * navegação.
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
