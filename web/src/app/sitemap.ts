import type { MetadataRoute } from "next";
import { listarDadosDeMunicipios, listarIdsParaPreRender } from "@/lib/api/cliente";
import { SITE } from "@/lib/site";

/**
 * O sitemap do site.
 *
 * Até 29/09/2026 `/sitemap.xml` respondia **200 com HTML** — 8 pedidos de
 * buscador por semana recebendo o corpo do `/404.html`. A correção da borda
 * daquele dia passou a devolver 404 real para caminho inexistente, mas aqui a
 * resposta certa nunca foi negar: era publicar.
 *
 * -- As mesmas funções do pré-render, de propósito --------------------------
 *
 * `listarIdsParaPreRender` e `listarDadosDeMunicipios` são exatamente as que
 * alimentam os `generateStaticParams` de `/politicos/[id]` e de
 * `/municipios/[uf]/[municipio]`. Duplicar a consulta aqui abriria a única
 * falha que um sitemap pode ter e ninguém percebe: listar URL que não existe,
 * ou omitir URL que existe. Vindo da mesma fonte, os dois não podem divergir.
 *
 * É por isso que os ~27 mil candidatos SEM pré-render ficam de fora. Eles
 * abrem no navegador pelo fallback de cliente, mas o HTML que o servidor
 * entrega ali é a casca do 404 — oferecer isso a um buscador é pedir que ele
 * indexe 27 mil páginas cujo conteúdo, para ele, é "não encontrada".
 *
 * -- Sem `lastModified` ------------------------------------------------------
 *
 * Não é esquecimento. Não sabemos quando uma página mudou: a API não expõe
 * data por político nem por município, e a data que teríamos à mão é a da
 * execução da ingestão. Usá-la afirmaria que as 2.294 páginas mudaram sempre
 * que QUALQUER fonte rodou — diário, portanto. Um `lastmod` que o buscador
 * descobre ser falso é pior que ausente: ele passa a ignorar o campo.
 *
 * -- Sem `/proposicoes/*` e `/votacoes/*` ------------------------------------
 *
 * São 58.276 páginas cujo pré-render está em decisão para ~02/10/2026 (ver
 * docs/CUSTOS_INFRA_AWS.md). Anunciá-las agora convidaria o rastreamento de
 * páginas que podem deixar de existir em três dias, e passaria o limite de
 * 50.000 URLs por arquivo, exigindo índice de sitemaps. Se a decisão for
 * manter, entram aqui — e aí com índice.
 */
/**
 * Exigido pelo `output: export`: sem isto o build para com "export const
 * dynamic não configurado nesta rota". Route handler é dinâmico por padrão no
 * App Router, e export estático não admite rota dinâmica — a declaração diz
 * que este arquivo é avaliado no build, não a cada pedido.
 */
export const dynamic = "force-static";

export default async function sitemap(): Promise<MetadataRoute.Sitemap> {
  const [ids, municipios] = await Promise.all([
    listarIdsParaPreRender(),
    listarDadosDeMunicipios(),
  ]);

  // Com barra no fim porque `trailingSlash: true`: é a URL canônica que o
  // site serve. Sem ela, o sitemap aponta para um endereço que redireciona.
  const estaticas = ["/", "/sobre/", "/dados-abertos/"];

  return [
    ...estaticas.map((rota) => ({ url: `${SITE}${rota}` })),
    ...ids.map((id) => ({ url: `${SITE}/politicos/${id}/` })),
    // `encodeURIComponent` no nome, não no caminho todo: sete das 1.596
    // cidades com dado têm apóstrofo (DIAS D'ÁVILA, MIRASSOL D'OESTE...) e
    // todas têm espaço ou acento. Conferido contra produção em 29/09/2026:
    // /municipios/BA/DIAS%20D'%C3%81VILA/ serve a página real.
    ...municipios.map((m) => ({
      url: `${SITE}/municipios/${m.uf}/${encodeURIComponent(m.municipio)}/`,
    })),
  ];
}
