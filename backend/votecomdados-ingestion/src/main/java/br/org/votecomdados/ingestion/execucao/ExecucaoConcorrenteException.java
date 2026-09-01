package br.org.votecomdados.ingestion.execucao;

import br.org.votecomdados.core.dominio.Enums.Fonte;

/**
 * Já existe uma execução viva para esta fonte.
 *
 * <p>É condição esperada, não defeito: o cron dispara todo dia, e um backfill
 * que passe de 24h faz o disparo seguinte encontrar o anterior ainda vivo. O
 * job encerra com log — <b>não enfileira</b>. Enfileirar transformaria um
 * atraso em uma fila crescente de processos concorrentes disputando o mesmo
 * watermark, que é justamente o que o B6 existe para impedir.
 */
public class ExecucaoConcorrenteException extends RuntimeException {

    public ExecucaoConcorrenteException(Fonte fonte) {
        super("já existe execução em andamento para a fonte " + fonte
              + "; encerrando sem enfileirar");
    }
}
