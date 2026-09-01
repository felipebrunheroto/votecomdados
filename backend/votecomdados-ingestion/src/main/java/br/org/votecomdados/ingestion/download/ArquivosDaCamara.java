package br.org.votecomdados.ingestion.download;

import java.net.URI;
import java.util.List;

/**
 * Endereços dos arquivos anuais da Câmara.
 *
 * <p>Nomes e caminhos verificados contra o portal em 30-31/08/2026. Ficam em
 * um lugar só porque são contrato com terceiro: quando a Câmara mudar um
 * caminho, é aqui que se corrige, e não espalhado por jobs.
 */
public final class ArquivosDaCamara {

    private static final String BASE = "https://dadosabertos.camara.leg.br/arquivos/";

    private ArquivosDaCamara() {}

    public static URI votacoes(int ano) {
        return uri("votacoes", ano);
    }

    public static URI votos(int ano) {
        return uri("votacoesVotos", ano);
    }

    public static URI proposicoes(int ano) {
        return uri("proposicoes", ano);
    }

    public static URI temas(int ano) {
        return uri("proposicoesTemas", ano);
    }

    public static URI autores(int ano) {
        return uri("proposicoesAutores", ano);
    }

    /** Cadastro completo, sem recorte por ano. */
    public static URI deputados() {
        return URI.create(BASE + "deputados/csv/deputados.csv");
    }

    /**
     * A ordem importa: a carga de votos referencia a votação por FK, e a de
     * temas e autoria referencia a proposição.
     */
    public static List<URI> doAno(int ano) {
        return List.of(proposicoes(ano), temas(ano), autores(ano),
                       votacoes(ano), votos(ano));
    }

    private static URI uri(String conjunto, int ano) {
        return URI.create(BASE + conjunto + "/csv/" + conjunto + "-" + ano + ".csv");
    }
}
