package br.org.votecomdados.ingestion.identidade;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * O schema modelava curadoria desde o início — {@code revisado_por},
 * {@code revisado_em}, e uma restrição que impede marcar revisado sem dizer
 * quem e quando. Nenhum código de produção jamais escreveu nessas colunas: em
 * 09/09/2026 eram 128 vínculos por similaridade e zero revisados.
 */
@SpringBootTest
@Testcontainers
class CuradoriaDeVinculosTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("votecomdados");

    @DynamicPropertySource
    static void configurar(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registro.add("spring.datasource.username", POSTGRES::getUsername);
        registro.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired CuradoriaDeVinculos curadoria;
    @Autowired JdbcClient jdbc;

    private UUID pessoa;

    @BeforeEach
    void limpar() {
        jdbc.sql("DELETE FROM identificador_externo").update();
        jdbc.sql("DELETE FROM politico").update();
        pessoa = jdbc.sql("""
            INSERT INTO politico (nome_civil, nome_urna)
            VALUES ('LUCIANE PEREIRA DA SILVA', 'LUCY DA SILVA') RETURNING id
            """).query(UUID.class).single();
    }

    private void vinculo(String sistema, String id, String metodo, String score) {
        jdbc.sql("""
            INSERT INTO identificador_externo
                (politico_id, sistema, identificador, metodo_resolucao, score_confianca)
            VALUES (:p, :s::fonte_enum, :i, :m::metodo_resolucao_enum, :sc::numeric)
            """).param("p", pessoa).param("s", sistema).param("i", id)
            .param("m", metodo).param("sc", score).update();
    }

    @Test
    void lista_so_o_que_e_fuzzy_e_ainda_nao_foi_revisado() {
        vinculo("CAMARA", "2319", "FUZZY", "0.8519");
        vinculo("SENADO", "59", "DETERMINISTICO", null);

        var pendentes = curadoria.pendentes();

        assertThat(pendentes).hasSize(1);
        assertThat(pendentes.getFirst().identificador()).isEqualTo("2319");
        assertThat(pendentes.getFirst().nomeUrna())
            .as("o nome de urna e o que permite decidir")
            .isEqualTo("LUCY DA SILVA");
    }

    /** O menos confiável primeiro: é por onde a revisão deve começar. */
    @Test
    void ordena_do_score_mais_baixo_para_o_mais_alto() {
        vinculo("CAMARA", "alto", "FUZZY", "0.99");
        vinculo("CAMARA", "baixo", "FUZZY", "0.86");

        assertThat(curadoria.pendentes())
            .extracting(CuradoriaDeVinculos.Pendente::identificador)
            .containsExactly("baixo", "alto");
    }

    @Test
    void aprovar_registra_quem_e_quando() {
        vinculo("CAMARA", "2319", "FUZZY", "0.8519");

        assertThat(curadoria.aprovar("CAMARA", "2319", "felipe")).isTrue();

        var r = jdbc.sql("""
            SELECT revisado_manualmente, revisado_por, revisado_em IS NOT NULL AS tem_data
              FROM identificador_externo WHERE identificador = '2319'
            """).query((rs, n) -> rs.getBoolean(1) + "|" + rs.getString(2) + "|" + rs.getBoolean(3))
            .single();

        assertThat(r).isEqualTo("true|felipe|true");
        assertThat(curadoria.pendentes()).as("sai da fila").isEmpty();
    }

    /**
     * Rejeitar APAGA. Marcar como "revisado e errado" deixaria o vínculo
     * atribuindo voto à pessoa errada, por mais bem documentado que estivesse.
     */
    @Test
    void rejeitar_remove_o_vinculo_em_vez_de_apenas_marcar() {
        vinculo("CAMARA", "2319", "FUZZY", "0.8519");

        assertThat(curadoria.rejeitar("CAMARA", "2319", "felipe")).isTrue();

        assertThat(jdbc.sql("SELECT count(*) FROM identificador_externo")
            .query(Long.class).single())
            .as("o vinculo errado nao pode sobreviver a decisao")
            .isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM politico").query(Long.class).single())
            .as("a pessoa fica: o errado era o vinculo, nao ela")
            .isEqualTo(1);
    }

    @Test
    void nao_mexe_em_vinculo_deterministico() {
        vinculo("CAMARA", "204554", "DETERMINISTICO", null);

        assertThat(curadoria.aprovar("CAMARA", "204554", "felipe")).isFalse();
        assertThat(curadoria.rejeitar("CAMARA", "204554", "felipe")).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM identificador_externo")
            .query(Long.class).single()).isEqualTo(1);
    }

    /** Dizer "nao encontrei" e melhor que dizer "aprovei" sem ter aprovado. */
    @Test
    void alvo_inexistente_devolve_falso() {
        assertThat(curadoria.aprovar("CAMARA", "nao-existe", "felipe")).isFalse();
    }

    @Test
    void aprovar_duas_vezes_nao_reescreve_a_primeira_decisao() {
        vinculo("CAMARA", "2319", "FUZZY", "0.8519");
        curadoria.aprovar("CAMARA", "2319", "felipe");

        assertThat(curadoria.aprovar("CAMARA", "2319", "outra-pessoa"))
            .as("ja revisado sai do escopo da curadoria")
            .isFalse();
        assertThat(jdbc.sql("SELECT revisado_por FROM identificador_externo")
            .query(String.class).single()).isEqualTo("felipe");
    }
}
