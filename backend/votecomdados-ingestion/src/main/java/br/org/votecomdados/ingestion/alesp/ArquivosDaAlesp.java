package br.org.votecomdados.ingestion.alesp;

import java.net.URI;

/**
 * Endereços dos arquivos da Alesp.
 *
 * <p>Caminhos verificados contra o portal em 31/08/2026, e todos respondem
 * {@code Last-Modified} — o que faz o {@code BaixadorDeArquivos} e o watermark
 * funcionarem aqui exatamente como na Câmara.
 *
 * <h2>A Alesp publica tudo inteiro, sem recorte por ano</h2>
 *
 * Não há {@code -2026.xml}: cada arquivo é a série completa (132 MB de
 * proposituras, 144 MB de autoria). O incremental depende inteiramente do
 * {@code If-Modified-Since}, e os arquivos são regerados <b>diariamente</b> —
 * o {@code Last-Modified} de todos os seis era 31/08/2026 06:28.
 */
public final class ArquivosDaAlesp {

    private static final String BASE = "https://www.al.sp.gov.br/repositorioDados/";
    private static final String PL = BASE + "processo_legislativo/";

    private ArquivosDaAlesp() {}

    /** Votos individuais em comissão. 67 MB, 226 mil votos. */
    public static URI votacoesDeComissao() {
        return URI.create(PL + "comissoes_permanentes_votacoes.xml");
    }

    /**
     * Reuniões de comissão. É daqui que sai a DATA da votação — o arquivo de
     * votos não tem nenhuma —, ligada por {@code IdReuniao}.
     */
    public static URI reunioesDeComissao() {
        return URI.create(PL + "comissoes_permanentes_reunioes.xml");
    }

    /** Comissões, para o nome da comissão na descrição da votação. */
    public static URI comissoes() {
        return URI.create(PL + "comissoes.xml");
    }

    /** Proposituras. Zip com um único XML de 132 MB. */
    public static URI proposituras() {
        return URI.create(PL + "proposituras.zip");
    }

    /** Autoria. Zip com um único XML de 144 MB, 1,1 milhão de vínculos. */
    public static URI autoria() {
        return URI.create(PL + "documento_autor.zip");
    }

    /**
     * Natureza do documento: é o que traduz {@code IdNatureza} para a sigla
     * (PL, PEC, Indicação...). Sem ele, {@code proposicao.sigla_tipo} seria um
     * número sem significado para o leitor.
     */
    public static URI naturezas() {
        return URI.create(PL + "naturezasSpl.xml");
    }

    /**
     * Cadastro de deputados. Só os <b>94 em exercício</b> — não é histórico.
     * Quem votou em 2010 e não está mais na Casa não aparece aqui.
     */
    public static URI deputados() {
        return URI.create(BASE + "deputados/deputados.xml");
    }

    /** Página oficial da propositura, para {@code proposicao.url_tramitacao}. */
    public static String urlDaPropositura(String idDocumento) {
        return "https://www.al.sp.gov.br/propositura/" + idDocumento;
    }

    /**
     * Página oficial da reunião de comissão, para {@code votacao.url_fonte}.
     *
     * <p>A Alesp não publica página por votação — a deliberação de uma matéria
     * é um item da pauta da reunião. Apontar para a reunião é o endereço mais
     * específico que existe do lado da fonte.
     */
    public static String urlDaReuniao(String idReuniao) {
        return "https://www.al.sp.gov.br/comissao/reuniao/" + idReuniao;
    }
}
