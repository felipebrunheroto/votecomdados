package br.org.votecomdados.ingestion.identidade;

import br.org.votecomdados.core.dominio.Enums.Fonte;
import java.time.LocalDate;

/**
 * Um parlamentar como a Casa o cadastra — antes de sabermos se é alguém da
 * coorte.
 *
 * <p>{@code nomeCivil} e {@code dataNascimento} são o par que resolve a
 * identidade. Não é escolha de conveniência: verificado em 30/08/2026 que a
 * coluna {@code cpf} de {@code deputados.csv} vem <b>vazia nas 7.889 linhas</b>,
 * então o CPF não serve para casar TSE↔Câmara. Os dois campos abaixo, sim —
 * estão 100% preenchidos da legislatura 54 (2011) em diante.
 */
public record ParlamentarDaCasa(
    Fonte fonte,
    String identificador,
    String nomeParlamentar,
    String nomeCivil,
    LocalDate dataNascimento,
    String uf
) {}
