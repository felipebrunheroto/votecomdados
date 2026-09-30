/**
 * Endereço público do site.
 *
 * Existe porque `sitemap.xml` e `robots.txt` exigem URL absoluta — caminho
 * relativo ali é inválido, não só feio. Ficava repetido em nenhum lugar
 * porque, até 29/09/2026, o site não publicava nenhum dos dois.
 *
 * Constante e não variável de ambiente de propósito: é um domínio único, já
 * declarado em `infra/variables.tf`, e um `NEXT_PUBLIC_SITE_URL` ausente no
 * build produziria um sitemap com `undefined/politicos/...` — silenciosamente
 * válido como XML e inútil como sitemap.
 */
export const SITE = "https://votecomdados.com.br";
