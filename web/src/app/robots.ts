import type { MetadataRoute } from "next";
import { SITE } from "@/lib/site";

/**
 * Até 29/09/2026 `/robots.txt` respondia **200 com HTML** — o corpo do
 * `/404.html`, pelo soft-404 que valia para todo caminho inexistente. Para um
 * buscador isso é pior que 404: ele recebe um arquivo de regras que não
 * consegue interpretar.
 *
 * Não há nada a proibir. O site é público por definição, não tem área
 * autenticada, e as rotas que não devem ser rastreadas (perfil sem
 * pré-render) já não constam do sitemap nem são linkadas. Uma regra
 * `Disallow` inventada aqui deixaria de fora páginas que queremos indexadas.
 *
 * O destino de `/proposicoes/*` e `/votacoes/*` está em decisão (ver
 * docs/CUSTOS_INFRA_AWS.md § pré-render). Se o corte acontecer, o lugar de
 * registrar isso é aqui e no sitemap — não antes.
 */
/**
 * Exigido pelo `output: export`: sem isto o build para com "export const
 * dynamic não configurado nesta rota". Route handler é dinâmico por padrão no
 * App Router, e export estático não admite rota dinâmica — a declaração diz
 * que este arquivo é avaliado no build, não a cada pedido.
 */
export const dynamic = "force-static";

export default function robots(): MetadataRoute.Robots {
  return {
    rules: [{ userAgent: "*", allow: "/" }],
    sitemap: `${SITE}/sitemap.xml`,
  };
}
