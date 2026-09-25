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
// Runtime cloudfront-js-2.0. Evito endsWith/includes de propósito e uso
// indexOf: o custo de uma incompatibilidade aqui é o site inteiro fora do ar.

// Extensões e prefixos que este site nunca serve. Tudo aqui é sonda.
var SONDA = /(^|\/)\.|\.(php|phtml|asp|aspx|jsp|cgi|pl|sh|sql|bak|old|swp|save|ini|conf|cfg|ya?ml|log)$|(^|\/)wp-/i;

// Exceção: /.well-known/ é caminho legítimo e padronizado (security.txt,
// validações de domínio). Começa com ponto e cairia na regra acima.
var BEM_CONHECIDO = /^\/\.well-known\//;

function handler(event) {
    var request = event.request;
    var uri = request.uri;

    if (SONDA.test(uri) && !BEM_CONHECIDO.test(uri)) {
        return {
            statusCode: 404,
            statusDescription: 'Not Found',
            headers: {
                'content-type': { value: 'text/plain; charset=utf-8' },
                // Sem cache: se um dia um caminho desses passar a ser
                // legítimo, não quero um 404 preso na borda por um dia.
                'cache-control': { value: 'no-store' }
            },
            body: 'Nao encontrado.\n'
        };
    }

    // Diretório explícito: /sobre/ -> /sobre/index.html
    if (uri.charAt(uri.length - 1) === '/') {
        request.uri = uri + 'index.html';
        return request;
    }

    // Sem barra e sem extensão: /sobre -> /sobre/index.html
    // O teste é pelo último segmento, não pela URI inteira: um caminho como
    // /dados-abertos/2026-09-25/arquivo tem ponto ANTES do último segmento.
    var ultimaBarra = uri.lastIndexOf('/');
    var ultimoSegmento = uri.substring(ultimaBarra + 1);
    if (ultimoSegmento.indexOf('.') === -1 && ultimoSegmento.length > 0) {
        request.uri = uri + '/index.html';
    }

    return request;
}
