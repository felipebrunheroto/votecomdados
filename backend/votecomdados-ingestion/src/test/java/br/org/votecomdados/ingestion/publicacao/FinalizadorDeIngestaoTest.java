package br.org.votecomdados.ingestion.publicacao;

import static org.assertj.core.api.Assertions.assertThat;

import br.org.votecomdados.core.dominio.Enums.Fonte;
import br.org.votecomdados.core.dominio.Enums.TipoJob;
import br.org.votecomdados.ingestion.execucao.ControleDeExecucaoService;
import br.org.votecomdados.ingestion.execucao.Execucao;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
 * O que fecha uma ingestão: marcar atuação, DEPOIS reconstruir a projeção.
 *
 * <p>A ordem é o ponto — {@code perfil_leitura} copia
 * {@code possui_atuacao_legislativa}, e reconstruir antes de marcar
 * publicaria o valor velho. O achado A1 (01/09/2026) foi justamente essa
 * marcação não existir em lugar nenhum do pipeline; os invariantes T66-T69
 * cobrem a função SQL isoladamente, este teste cobre o serviço que a
 * encadeia com a projeção.
 */
@SpringBootTest
@Testcontainers
class FinalizadorDeIngestaoTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("votecomdados");

    @DynamicPropertySource
    static void configurar(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registro.add("spring.datasource.username", POSTGRES::getUsername);
        registro.add("spring.datasource.password", POSTGRES::getPassword);
        registro.add("spring.flyway.enabled", () -> true);
    }

    @Autowired FinalizadorDeIngestao finalizador;
    @Autowired ControleDeExecucaoService controle;
    @Autowired JdbcClient jdbc;

    Execucao execucao;

    @BeforeEach
    void abrir() {
        execucao = controle.iniciar(Fonte.CAMARA, TipoJob.BACKFILL, "{}");
    }

    @AfterEach
    void limpar() {
        controle.close();
        jdbc.sql("DELETE FROM politico").update();
        jdbc.sql("DELETE FROM ingestao_execucao").update();
    }

    /**
     * O teste que prova a ordem: se a projeção fosse reconstruída ANTES da
     * marcação, {@code perfil_leitura} carregaria {@code false} — o valor de
     * antes desta chamada — mesmo a coluna de origem já estando correta.
     */
    @Test
    void marca_atuacao_antes_de_reconstruir_a_projecao() {
        var id = candidato("Maria Da Silva");
        autoria(id);

        finalizador.finalizar(execucao);

        assertThat(jdbc.sql("SELECT possui_atuacao_legislativa FROM politico WHERE id = :id")
            .param("id", id).query(Boolean.class).single()).isTrue();

        assertThat(jdbc.sql("""
                SELECT possui_atuacao_legislativa FROM perfil_leitura WHERE politico_id = :id
                """).param("id", id).query(Boolean.class).single()).isTrue();
    }

    @Test
    void quem_nao_tem_nenhum_sinal_fica_sem_perfil_pre_calculavel() {
        var id = candidato("Sem Atuacao Nenhuma");

        finalizador.finalizar(execucao);

        assertThat(jdbc.sql("SELECT possui_atuacao_legislativa FROM politico WHERE id = :id")
            .param("id", id).query(Boolean.class).single()).isFalse();
    }

    @Test
    void reexecutar_apos_perder_o_sinal_reflete_no_perfil_publicado() {
        var id = candidato("Perde Atuacao");
        long proposicaoId = autoria(id);

        finalizador.finalizar(execucao);
        assertThat(jdbc.sql("""
                SELECT possui_atuacao_legislativa FROM perfil_leitura WHERE politico_id = :id
                """).param("id", id).query(Boolean.class).single()).isTrue();

        jdbc.sql("DELETE FROM proposicao_autor WHERE proposicao_id = :p AND politico_id = :id")
            .param("p", proposicaoId).param("id", id).update();
        finalizador.finalizar(execucao);

        assertThat(jdbc.sql("""
                SELECT possui_atuacao_legislativa FROM perfil_leitura WHERE politico_id = :id
                """).param("id", id).query(Boolean.class).single()).isFalse();
    }

    private UUID candidato(String nome) {
        var id = jdbc.sql("INSERT INTO politico (nome_civil) VALUES (:n) RETURNING id")
            .param("n", nome).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO candidatura (politico_id, sq_candidato_tse, ano_eleicao, cargo,
                                         esfera, uf, partido_sigla, status)
                VALUES (:id, 'sq-' || gen_random_uuid(), 2026, 'DEPUTADO_FEDERAL',
                        'FEDERAL', 'SP', 'XYZ', 'DEFERIDO')
                """).param("id", id).update();
        return id;
    }

    private long autoria(UUID politicoId) {
        long proposicaoId = jdbc.sql("""
                INSERT INTO proposicao (casa, id_externo, sigla_tipo, numero, ano, ementa,
                                        url_tramitacao)
                VALUES ('CAMARA', 'finalizador-teste-' || gen_random_uuid(), 'PL', 1, 2026,
                        'Ementa de teste', 'https://exemplo')
                RETURNING id
                """).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO proposicao_autor (proposicao_id, politico_id, autor_nome)
                VALUES (:p, :id, 'Autor de teste')
                """).param("p", proposicaoId).param("id", politicoId).update();
        return proposicaoId;
    }
}
