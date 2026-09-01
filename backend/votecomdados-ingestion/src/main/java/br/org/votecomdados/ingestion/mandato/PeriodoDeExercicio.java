package br.org.votecomdados.ingestion.mandato;

import java.time.LocalDate;

/**
 * Um trecho contínuo em que o parlamentar esteve numa mesma situação.
 *
 * <p>{@code fim} nulo significa "ainda vigente". {@code situacaoOrigem} guarda
 * a string literal da Casa — mesma regra do {@code voto_origem}: o enum é
 * interpretação nossa, a string é o fato.
 */
public record PeriodoDeExercicio(
    String situacaoOrigem,
    String condicaoOrigem,
    LocalDate inicio,
    LocalDate fim
) {}
