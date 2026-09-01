/**
 * Link para a fonte oficial. Componente único de propósito: o requisito de
 * neutralidade exige que toda matéria e votação exibida leve o leitor de volta
 * à origem, e centralizar isso impede que alguma tela esqueça.
 */
export function LinkFonteOficial({
  href, children = "Ver na fonte oficial", descricaoAcessivel,
}: {
  href: string;
  children?: React.ReactNode;
  descricaoAcessivel?: string;
}) {
  return (
    <a
      href={href}
      target="_blank"
      rel="noopener noreferrer"
      className="inline-flex items-center gap-1 text-sm font-medium text-acento underline underline-offset-2 hover:no-underline"
    >
      {children}
      {/* O contexto extra fica só para leitor de tela: numa lista de 20 itens,
          "Ver na fonte oficial" repetido 20 vezes é inútil sem qualificação. */}
      {descricaoAcessivel && <span className="sr-only"> — {descricaoAcessivel}</span>}
      <span aria-hidden="true">↗</span>
      <span className="sr-only">(abre em nova aba)</span>
    </a>
  );
}
