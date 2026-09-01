package br.org.votecomdados.ingestion.coorte;

import br.org.votecomdados.core.dominio.Enums.Cargo;
import br.org.votecomdados.core.dominio.Enums.Esfera;
import br.org.votecomdados.core.dominio.Enums.StatusCandidatura;
import java.time.LocalDate;

/**
 * Uma candidatura já normalizada, pronta para virar linha no schema curado.
 *
 * <p>{@code cpfHmac} chega calculado: o CPF em claro morre no leitor, antes de
 * qualquer coisa ser gravada ou logada.
 */
public record CandidaturaDoTse(
    String sqCandidato,
    int anoEleicao,
    int turno,
    Cargo cargo,
    Esfera esfera,
    String uf,
    String municipio,
    String codigoMunicipioTse,
    String nomeCivil,
    String nomeUrna,
    String cpfHmac,
    LocalDate dataNascimento,
    String genero,
    String partidoSigla,
    Integer partidoNumero,
    Integer numeroUrna,
    StatusCandidatura status,
    Boolean eleito
) {}
