import type { NextConfig } from "next";

/**
 * `output: "export"` só importa de verdade para `next build` — é o comando
 * que gera o artefato que vai pro S3. Só que o Next 16 aplica as MESMAS
 * restrições também em `next dev`: nenhuma rota dinâmica fora do que
 * `generateStaticParams` devolve, nem em desenvolvimento (confirmado na doc
 * bundlada do próprio Next, `node_modules/next/dist/docs/.../static-exports.md`:
 * *"Attempting to use any of these features with `next dev` will result in
 * an error"*). Como `/politicos/[id]` só pré-renderiza quem tem atuação
 * legislativa — a MINORIA da coorte — isso travava a navegação local de
 * praticamente qualquer candidato, com OU sem Docker (achado B2, ver
 * docs/PLANO_CORRECAO_STATIC_PARAMS.md).
 *
 * A saída: `output: "export"` só entra durante `next build`, nunca durante
 * `next dev`. O Next define `NODE_ENV` sozinho por comando — `development`
 * no dev, `production` no build — então não existe cenário de projeto em que
 * um `next dev` acabe rodando com `NODE_ENV=production` e herdando a
 * restrição por engano (o `start` deste projeto serve o export estático via
 * `serve`, nunca via `next start`). Sem isso, não sobra nenhuma razão para o
 * `next dev` impor a restrição: ele não gera `out/`, então nunca foi um
 * simulador real do export — quem verifica o export de verdade é
 * `next build` seguido de `scripts/servir-com-fallback.mjs`.
 */
const nextConfig: NextConfig = {
  output: process.env.NODE_ENV === "production" ? "export" : undefined,

  // Emite /politicos/<id>/index.html em vez de /politicos/<id>.html, que é o
  // que S3 + CloudFront servem sem configuração extra de roteamento.
  trailingSlash: true,

  images: {
    // O otimizador de imagem do Next exige servidor e não funciona em export
    // estático. As fotos de candidato já chegam redimensionadas do pipeline
    // de ingestão (ver docs/FRONTEND.md § 1).
    unoptimized: true,
  },
};

export default nextConfig;
