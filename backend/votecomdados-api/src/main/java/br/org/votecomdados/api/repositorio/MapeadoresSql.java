package br.org.votecomdados.api.repositorio;

import br.org.votecomdados.core.dominio.Enums.*;
import br.org.votecomdados.core.dominio.Modelo.*;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/** Conversões de ResultSet para os records do domínio. */
final class MapeadoresSql {

    private MapeadoresSql() {}

    static <E extends Enum<E>> E enumOuNulo(String valor, Class<E> tipo) {
        return valor == null ? null : Enum.valueOf(tipo, valor);
    }

    /** Postgres devolve boolean nulo como `false` em getBoolean(); wasNull() é o que distingue. */
    static Boolean booleanOuNulo(ResultSet rs, String coluna) throws SQLException {
        boolean v = rs.getBoolean(coluna);
        return rs.wasNull() ? null : v;
    }

    static Long longOuNulo(ResultSet rs, String coluna) throws SQLException {
        long v = rs.getLong(coluna);
        return rs.wasNull() ? null : v;
    }

    static Integer intOuNulo(ResultSet rs, String coluna) throws SQLException {
        int v = rs.getInt(coluna);
        return rs.wasNull() ? null : v;
    }

    static LocalDate dataOuNula(ResultSet rs, String coluna) throws SQLException {
        var d = rs.getDate(coluna);
        return d == null ? null : d.toLocalDate();
    }

    static List<String> textoArray(ResultSet rs, String coluna) throws SQLException {
        Array a = rs.getArray(coluna);
        if (a == null) return List.of();
        String[] valores = (String[]) a.getArray();
        return Arrays.stream(valores).filter(java.util.Objects::nonNull).toList();
    }

    static Candidatura candidatura(ResultSet rs) throws SQLException {
        return new Candidatura(
            rs.getInt("ano_eleicao"),
            Cargo.valueOf(rs.getString("cargo")),
            Esfera.valueOf(rs.getString("esfera")),
            rs.getString("uf"),
            rs.getString("municipio"),
            rs.getString("partido_sigla"),
            StatusCandidatura.valueOf(rs.getString("status")),
            booleanOuNulo(rs, "eleito"));
    }

    static Cobertura cobertura(ResultSet rs) throws SQLException {
        return new Cobertura(
            Esfera.valueOf(rs.getString("esfera")),
            rs.getString("uf"),
            enumOuNulo(rs.getString("casa"), CasaLegislativa.class),
            rs.getString("recurso"),
            StatusCobertura.valueOf(rs.getString("status")),
            dataOuNula(rs, "disponivel_desde"),
            rs.getString("observacao"));
    }
}
