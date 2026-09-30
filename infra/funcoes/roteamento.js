// CloudFront Function (viewer-request) da distribuição do site.
//
// Resolve DOIS problemas encontrados em produção em 25/09/2026.
//
// -- 1. Subpágina nenhuma era servida ------------------------------------
//
// `next.config.ts` diz, em comentário, que `trailingSlash: true` emite
// `/sobre/index.html` "que é o que S3 + CloudFront servem sem configuração
// extra de roteamento". Isso vale para origem S3 *website*. NÃO vale para
// origem S3 REST com OAC, que é a nossa: `default_root_object` só se aplica
// à raiz da distribuição, nunca a subpastas.
//
// Consequência medida: `/sobre/` pedia a chave `sobre/` ao S3, que não
// existe, virava 403 e era reescrito para 200 + `/404.html`. Num navegador
// de verdade, "Sobre os dados" e "Dados abertos" mostravam **"Página não
// encontrada"** — e o rodapé linka para as duas. Os perfis abriam só porque
// o fallback de cliente os resgata, o que significa que nem os
// pré-renderizados entregavam HTML pronto: `generateStaticParams` existia e
// não servia para nada, inclusive para indexação em buscador.
//
// -- 2. Toda sonda recebia 200 --------------------------------------------
//
// Com tudo caindo no 404.html reescrito para 200, `/.env`, `/.git/config` e
// afins respondiam **200**. Para um scanner, 200 quer dizer "existe": foi
// por isso que um deles varreu oito variantes de `.env` e elas ocuparam
// metade da lista de páginas mais acessadas da semana. Nada vazou — mas
// estávamos anunciando que tudo existe.
//
// A documentação da AWS garante que este caminho funciona: "If your
// CloudFront Function is configured to return an HTTP error of 400 or above,
// your viewer will not see a custom error page that you have specified for
// the same status code." Ou seja, o 404 daqui escapa da reescrita para 200.
//
// -- 3. Todo caminho INEXISTENTE ainda recebia 200 ------------------------
//
// Medido em 29/09/2026, contra producao: `/naoexiste/`, `/login`, `/admin`,
// `/dashboard`, `/api`, `/config`, `/credentials`, `/Dockerfile` e
// `/sitemap.xml` respondiam **200** com o corpo de `/404.html`. A regra 2
// acima so pegava o que PARECE sonda (ponto inicial, `.php`, `wp-`); um
// caminho comum e inexistente continuava caindo na reescrita para 200.
//
// O 200 de `custom_error_response` NAO e descuido: e o que sustenta o
// fallback de cliente de `/politicos/{uuid}/` -- ~27 mil candidatos sem
// pre-render que o navegador resgata (verificado em 29/09/2026: AARON
// SALLES TORRES, sem pre-render, renderiza no navegador). Trocar aquele
// `response_code` por 404 quebraria justamente esses.
//
// Por isso a decisao fica AQUI, onde da para distinguir: o 200 vale para as
// FORMAS de rota que o site publica, e nao para qualquer URI. Uma forma
// conhecida sem objeto no S3 segue para o fallback; o resto recebe 404 de
// verdade. A lista abaixo e curta porque o site tem sete rotas -- ver
// `web/src/app`. ACRESCENTAR ROTA NOVA NO APP EXIGE ACRESCENTAR AQUI, e e
// por isso que existe `roteamento.test.mjs`, que roda no CI de infra.

// Runtime cloudfront-js-2.0. Evito endsWith/includes de propósito e uso
// indexOf: o custo de uma incompatibilidade aqui é o site inteiro fora do ar.

// Extensões e prefixos que este site nunca serve. Tudo aqui é sonda.
//
// A lista cresceu em 29/09/2026 com o que a varredura da semana de 22/09
// pediu de fato e a regra antiga deixava passar: `/web.config`,
// `/application.properties`, `/composer.lock`, `/backup.zip`,
// `/backup.tar.gz`, `/site.zip`, `/client.ovpn`, `/id_rsa.pub`,
// `/actuator/heapdump` (esta ultima sem ponto: cai na lista de rotas, nao
// aqui). Caminho COM extensao nao passa pela lista de rotas
// conhecidas (ele pode ser asset legitimo, e a borda nao sabe quais
// existem), entao so chega a 404 real quem casar aqui.
//
// `/sitemap.xml` e `/robots.txt` ficam de fora porque agora EXISTEM: o site
// passou a publicar os dois em 29/09/2026 (`web/src/app/sitemap.ts` e
// `robots.ts`). Antes respondiam 200 com HTML, e a resposta certa ali nunca
// foi negar -- era publicar. Como tem extensao, chegam ao S3 e encontram o
// objeto.
var SONDA = /(^|\/)\.|\.(php|phtml|asp|aspx|jsp|cgi|pl|sh|sql|bak|old|swp|save|ini|conf|config|cfg|ya?ml|log|properties|lock|dump|zip|tar|gz|tgz|rar|7z|ovpn|pem|key|crt|p12|pfx|pub)$|(^|\/)wp-/i;

// Exceção: /.well-known/ é caminho legítimo e padronizado (security.txt,
// validações de domínio). Começa com ponto e cairia na regra acima.
var BEM_CONHECIDO = /^\/\.well-known\//;

// As formas de rota que o site publica. Conferidas em 29/09/2026 contra os
// dados reais, nao contra os tipos: 695 politicos com atuacao (uuid), 50.073
// proposicoes e 8.203 votacoes (ids todos numericos, verificado em
// GET /proposicoes e GET /votacoes), 1.596 municipios como /{UF}/{nome}/ com
// o nome percent-encoded (TIET%C3%8A).
//
// `/municipios/` NAO entra: a pagina foi removida a pedido, e nada no app
// linka para ela. 26 acessos na semana de 22/09 sao anteriores a remocao.
var ROTAS = [
    /^\/$/,
    /^\/(sobre|dados-abertos)\/$/,
    /^\/politicos\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\/$/i,
    /^\/(proposicoes|votacoes)\/[0-9]+\/$/,
    /^\/municipios\/[A-Za-z]{2}\/[^\/]+\/$/
];

function conhecida(diretorio) {
    for (var i = 0; i < ROTAS.length; i++) {
        if (ROTAS[i].test(diretorio)) return true;
    }
    return false;
}

// Duas respostas de 404 de proposito. Sonda recebe texto puro -- scanner nao
// le pagina, e servir HTML a ele e so banda. Quem erra o endereco a mao e
// uma pessoa, e recebe algo navegavel: /municipios/ teve 26 acessos na semana
// de 22/09 (acessos, nao visitantes) e agora passa a negar -- com status
// honesto e corpo em texto puro, quem chegasse ali veria uma tela crua.
function naoEncontradoTexto() {
    return {
        statusCode: 404,
        statusDescription: 'Not Found',
        headers: {
            'content-type': { value: 'text/plain; charset=utf-8' },
            // Sem cache: se um dia um caminho desses passar a ser
            // legitimo, nao quero um 404 preso na borda por um dia.
            'cache-control': { value: 'no-store' }
        },
        body: 'Nao encontrado.\n'
    };
}

function naoEncontradoPagina() {
    return {
        statusCode: 404,
        statusDescription: 'Not Found',
        headers: {
            'content-type': { value: 'text/html; charset=utf-8' },
            'cache-control': { value: 'no-store' }
        },
        body: '<!doctype html><html lang="pt-BR"><meta charset="utf-8">'
            + '<meta name="viewport" content="width=device-width,initial-scale=1">'
            + '<title>Pagina nao encontrada \u00b7 VoteComDados</title>'
            + '<style>:root{color-scheme:light dark}'
            + 'body{margin:0;min-height:100vh;display:grid;place-items:center;'
            + 'font:16px/1.6 system-ui,sans-serif;padding:2rem}'
            + 'main{max-width:34rem}h1{font-size:1.5rem;margin:0 0 .75rem}'
            + 'p{margin:0 0 1rem;opacity:.8}a{color:inherit}</style>'
            + '<main><h1>Esta p\u00e1gina n\u00e3o existe</h1>'
            + '<p>O endere\u00e7o pedido n\u00e3o corresponde a nenhuma p\u00e1gina '
            + 'do VoteComDados.</p>'
            + '<p><a href="/">Ir para a p\u00e1gina inicial</a></p></main></html>'
    };
}

function handler(event) {
    var request = event.request;
    var uri = request.uri;

    if (SONDA.test(uri) && !BEM_CONHECIDO.test(uri)) {
        return naoEncontradoTexto();
    }

    // Pedido de ARQUIVO, nao de pagina: asset de /_next/, /404.html,
    // /municipios.json, /.well-known/security.txt, e tambem o payload RSC
    // que o Next busca na navegacao de cliente (/sobre/index.txt). Vai
    // direto ao S3 -- a borda nao tem como saber se a chave existe, e
    // adivinhar aqui quebraria asset legitimo.
    //
    // O teste e pelo ULTIMO SEGMENTO, nao pela URI inteira: um caminho como
    // /dados-abertos/2026-09-25/arquivo tem ponto ANTES do ultimo segmento.
    var ultimoSegmento = uri.substring(uri.lastIndexOf('/') + 1);
    if (ultimoSegmento.indexOf('.') !== -1) {
        return request;
    }

    // Daqui para baixo e pedido de PAGINA. `trailingSlash: true` faz o build
    // emitir sempre /rota/index.html, entao normalizo para a forma com barra
    // e decido uma vez -- serve /sobre e /sobre/ pelo mesmo caminho.
    var diretorio = uri.charAt(uri.length - 1) === '/' ? uri : uri + '/';

    if (!conhecida(diretorio)) {
        return naoEncontradoPagina();
    }

    request.uri = diretorio + 'index.html';
    return request;
}
