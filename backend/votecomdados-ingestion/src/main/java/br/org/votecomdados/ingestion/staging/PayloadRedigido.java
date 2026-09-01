package br.org.votecomdados.ingestion.staging;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Payload já sem os campos que não devem sair da memória.
 *
 * <p>{@code camposRedigidos} não é decoração: é a prova auditável de que a
 * redação aconteceu e de o que ela tirou. Sem esse registro, "o CPF não está
 * gravado" seria uma afirmação sem evidência — e a coluna
 * {@code staging.payload_bruto.campos_redigidos} existe exatamente para isso.
 */
public record PayloadRedigido(JsonNode payload, List<String> camposRedigidos) {}
