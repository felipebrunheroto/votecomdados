package br.org.votecomdados.ingestion.execucao;

import br.org.votecomdados.core.dominio.Enums.Fonte;
import br.org.votecomdados.core.dominio.Enums.TipoJob;
import java.time.Instant;

/**
 * Uma execução em andamento do worker.
 *
 * <p>{@code watermarkAnterior} é o marcador de onde o ciclo passado parou. É
 * ele que o job incremental usa para perguntar "o que mudou desde quando?", e
 * é {@code null} na primeira execução de uma fonte.
 */
public record Execucao(
    long id,
    Fonte fonte,
    TipoJob tipoJob,
    Instant watermarkAnterior
) {}
