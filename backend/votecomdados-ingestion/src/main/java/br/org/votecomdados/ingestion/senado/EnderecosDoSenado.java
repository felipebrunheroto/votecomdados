package br.org.votecomdados.ingestion.senado;

import java.net.URI;

/**
 * Endereços da API de dados abertos do Senado.
 *
 * <p>Verificados contra a API real em 01/09/2026.
 *
 * <h2>Não há recorte por ano de lançamento do cadastro</h2>
 *
 * O universo de parlamentares vem de UM intervalo de legislaturas, não de um
 * arquivo anual como a Câmara ou de uma série única como a Alesp. O intervalo
 * 50–57 foi escolhido para bater com a cobertura de candidaturas do TSE
 * (federal, desde 1994 — legislatura 50 começa em 1995-02-01): candidato de
 * 2026 que foi senador antes disso não é um caso que a coorte alcança de
 * nenhum jeito, porque a candidatura dele nem estaria carregada.
 *
 * <h2>Base parametrizada, pelo mesmo motivo de {@code OrquestradorDaAlesp.Enderecos}</h2>
 *
 * Sem isso não haveria como testar o ciclo do {@code OrquestradorDoSenado}
 * contra HTTP real sem bater no Senado em produção a cada build.
 */
public final class EnderecosDoSenado {

    private static final String BASE_PRODUCAO = "https://legis.senado.leg.br/dadosabertos";

    /** Primeira legislatura dentro da janela de cobertura do TSE (1995-02-01). */
    private static final int LEGISLATURA_INICIAL = 50;

    /** Legislatura corrente (2023-02-01 a 2027-01-31). */
    private static final int LEGISLATURA_FINAL = 57;

    private final String base;

    private EnderecosDoSenado(String base) {
        this.base = base;
    }

    public static EnderecosDoSenado producao() {
        return new EnderecosDoSenado(BASE_PRODUCAO);
    }

    public static EnderecosDoSenado comBase(String base) {
        return new EnderecosDoSenado(base);
    }

    /**
     * O universo de parlamentares que pode conter candidatos de 2026: todo
     * titular e suplente das legislaturas 50 a 57, num único {@code GET}.
     */
    public URI universoDeParlamentares() {
        return URI.create(base + "/senador/lista/legislatura/"
                          + LEGISLATURA_INICIAL + "/" + LEGISLATURA_FINAL);
    }

    /**
     * Detalhe de um parlamentar — é daqui que sai {@code DataNascimento}, que
     * a lista de legislatura não publica.
     */
    public URI detalheDoParlamentar(String codigo) {
        return URI.create(base + "/senador/" + codigo);
    }

    /**
     * Votações do ano. O parâmetro é {@code ano}, não um intervalo de datas —
     * {@code dataInicio=20260701} (sem hífen) responde 400; o formato aceito é
     * {@code AAAA-MM-DD}. Usar {@code ano} evita a armadilha de formato e
     * cobre o caso de uso inteiro do incremental.
     */
    public URI votacoesDoAno(int ano) {
        return URI.create(base + "/votacao?ano=" + ano);
    }
}
