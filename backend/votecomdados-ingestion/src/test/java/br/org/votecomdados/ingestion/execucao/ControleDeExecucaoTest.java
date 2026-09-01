package br.org.votecomdados.ingestion.execucao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.org.votecomdados.core.dominio.Enums.Fonte;
import br.org.votecomdados.core.dominio.Enums.TipoJob;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * As garantias do B6 contra um Postgres real.
 *
 * <p>O achado original era que duas execuções simultâneas leriam o mesmo
 * watermark inicial e a última a terminar gravaria o seu — possivelmente
 * ANTERIOR ao da outra. O dado não fica duplicado, fica <b>faltando</b>, e a
 * idempotência dos upserts não protege contra isso. Por ser invisível no dado,
 * só teste pega.
 */
@SpringBootTest
@Testcontainers
class ControleDeExecucaoTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("votecomdados");

    @DynamicPropertySource
    static void configurar(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registro.add("spring.datasource.username", POSTGRES::getUsername);
        registro.add("spring.datasource.password", POSTGRES::getPassword);
        // O schema vem das migrations do core; aqui o Flyway precisa rodar
        // porque não há API subindo antes.
        registro.add("spring.flyway.enabled", () -> true);
    }

    @Autowired ControleDeExecucaoService controle;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired TransactionTemplate transacao;

    /**
     * O "outro processo": um controle com conexão própria, para simular o
     * concorrente. Sem ele o teste de exclusão mútua seria mentira, porque o
     * mesmo objeto reusaria o lock que já tem.
     *
     * <p>Instanciado à mão, e não como bean: o segundo processo não faz parte
     * da aplicação — registrá-lo no contexto deixaria a injeção do
     * {@code SeletorDeJob} ambígua para provar algo sobre concorrência.
     */
    ControleDeExecucaoService controleConcorrente;

    @BeforeEach
    void criarOutroProcesso() {
        controleConcorrente = new ControleDeExecucaoService(dataSource, jdbc, transacao);
    }

    /**
     * A ordem da limpeza não é arbitrária: {@code voto_nominal_historico} e
     * {@code proposicao_historico} referenciam a execução que fez a alteração,
     * e o banco recusa apagar uma execução que ainda tem histórico apontando
     * para ela. É a trilha de auditoria se recusando a ficar órfã — apagar
     * primeiro o que ela documenta é o caminho, não relaxar a restrição.
     */
    @AfterEach
    void limpar() {
        controle.close();
        controleConcorrente.close();
        jdbc.sql("DELETE FROM politico").update();     // cascata: votos e histórico
        jdbc.sql("DELETE FROM votacao").update();
        jdbc.sql("DELETE FROM proposicao").update();   // cascata: histórico de ementa
        jdbc.sql("DELETE FROM ingestao_execucao").update();
    }

    @Test
    void abre_execucao_e_registra_o_job() {
        var e = controle.iniciar(Fonte.CAMARA, TipoJob.INCREMENTAL, "{}");

        assertThat(e.id()).isPositive();
        assertThat(e.watermarkAnterior()).as("primeira execucao nao tem marcador").isNull();
        assertThat(statusDe(e.id())).isEqualTo("EM_ANDAMENTO");
    }

    @Test
    void segunda_execucao_viva_na_mesma_fonte_e_recusada() {
        controle.iniciar(Fonte.CAMARA, TipoJob.BACKFILL, "{\"ano\": 2023}");

        assertThatThrownBy(() ->
                controleConcorrente.iniciar(Fonte.CAMARA, TipoJob.INCREMENTAL, "{}"))
            .isInstanceOf(ExecucaoConcorrenteException.class)
            .hasMessageContaining("sem enfileirar");
    }

    @Test
    void fontes_diferentes_correm_em_paralelo() {
        var camara = controle.iniciar(Fonte.CAMARA, TipoJob.INCREMENTAL, "{}");
        var senado = controleConcorrente.iniciar(Fonte.SENADO, TipoJob.INCREMENTAL, "{}");

        assertThat(camara.id()).isNotEqualTo(senado.id());
        assertThat(statusDe(camara.id())).isEqualTo("EM_ANDAMENTO");
        assertThat(statusDe(senado.id())).isEqualTo("EM_ANDAMENTO");
    }

    @Test
    void fonte_e_liberada_apos_conclusao() {
        var primeira = controle.iniciar(Fonte.CAMARA, TipoJob.INCREMENTAL, "{}");
        controle.concluir(primeira, Instant.now(), 10, 0);

        var segunda = controle.iniciar(Fonte.CAMARA, TipoJob.INCREMENTAL, "{}");
        assertThat(segunda.id()).isNotEqualTo(primeira.id());
    }

    @Test
    void watermark_avanca_no_sucesso_e_e_lido_pela_execucao_seguinte() {
        var primeira = controle.iniciar(Fonte.CAMARA, TipoJob.INCREMENTAL, "{}");
        var marcador = Instant.parse("2026-08-30T03:00:00Z");
        controle.concluir(primeira, marcador, 42, 0);

        var segunda = controle.iniciar(Fonte.CAMARA, TipoJob.INCREMENTAL, "{}");
        assertThat(segunda.watermarkAnterior()).isEqualTo(marcador);
    }

    /**
     * O coração do B6: um watermark que anda para trás faz a janela entre os
     * dois marcadores ser pulada em silêncio.
     */
    @Test
    void watermark_nunca_retrocede() {
        var recente = Instant.parse("2026-08-30T03:00:00Z");
        var antigo = recente.minus(10, ChronoUnit.DAYS);

        var primeira = controle.iniciar(Fonte.CAMARA, TipoJob.INCREMENTAL, "{}");
        controle.concluir(primeira, recente, 1, 0);

        var segunda = controle.iniciar(Fonte.CAMARA, TipoJob.BACKFILL, "{}");
        controle.concluir(segunda, antigo, 1, 0);

        assertThat(controle.watermarkAtual(Fonte.CAMARA)).contains(recente);
    }

    @Test
    void execucao_que_falha_nao_avanca_o_watermark() {
        var primeira = controle.iniciar(Fonte.CAMARA, TipoJob.INCREMENTAL, "{}");
        var marcador = Instant.parse("2026-08-30T03:00:00Z");
        controle.concluir(primeira, marcador, 1, 0);

        var segunda = controle.iniciar(Fonte.CAMARA, TipoJob.INCREMENTAL, "{}");
        controle.falhar(segunda, new IllegalStateException("fonte fora do ar"));

        assertThat(controle.watermarkAtual(Fonte.CAMARA))
            .as("a janela nao processada precisa continuar por processar")
            .contains(marcador);
        assertThat(statusDe(segunda.id())).isEqualTo("FALHA");
        assertThat(erroDe(segunda.id())).contains("fonte fora do ar");
    }

    /**
     * Processo morto por OOM ou evicção nunca marca FALHA. A linha órfã
     * travaria a fonte para sempre pelo índice único parcial — e é por segurar
     * o lock que dá para limpá-la sem risco de matar um job vivo.
     */
    @Test
    void reaper_limpa_execucao_orfa_de_processo_morto() {
        long orfa = jdbc.sql("""
                INSERT INTO ingestao_execucao (fonte, tipo_job, status, iniciado_em)
                VALUES ('CAMARA', 'BACKFILL', 'EM_ANDAMENTO', now() - interval '3 days')
                RETURNING id
                """).query(Long.class).single();

        var nova = controle.iniciar(Fonte.CAMARA, TipoJob.INCREMENTAL, "{}");

        assertThat(statusDe(orfa)).isEqualTo("FALHA");
        assertThat(erroDe(orfa)).contains("orfa");
        assertThat(statusDe(nova.id())).isEqualTo("EM_ANDAMENTO");
    }

    /**
     * Sem o {@code SET LOCAL}, a alteração feita pelo worker apareceria como
     * manual — que é a leitura correta para curadoria por SQL, e errada aqui.
     */
    @Test
    void alteracao_dentro_da_execucao_fica_atribuida_a_ela() {
        var execucao = controle.iniciar(Fonte.CAMARA, TipoJob.INCREMENTAL, "{}");

        long votoId = prepararVoto();
        controle.naExecucao(execucao, () ->
            jdbc.sql("UPDATE voto_nominal SET voto = 'NAO', voto_origem = 'Não' WHERE id = :id")
                .param("id", votoId).update());

        var atribuida = jdbc.sql("""
                SELECT execucao_id FROM voto_nominal_historico WHERE voto_nominal_id = :id
                """).param("id", votoId).query(Long.class).single();

        assertThat(atribuida).isEqualTo(execucao.id());
    }

    @Test
    void alteracao_fora_da_execucao_fica_marcada_como_manual() {
        controle.iniciar(Fonte.CAMARA, TipoJob.INCREMENTAL, "{}");

        long votoId = prepararVoto();
        jdbc.sql("UPDATE voto_nominal SET voto = 'ABSTENCAO', voto_origem = 'Abstenção' WHERE id = :id")
            .param("id", votoId).update();

        var atribuida = jdbc.sql("""
                SELECT execucao_id FROM voto_nominal_historico WHERE voto_nominal_id = :id
                """).param("id", votoId).query(Long.class).optional();

        assertThat(atribuida).as("curadoria por SQL nao tem execucao").isEmpty();
    }

    // ---------------------------------------------------------------- auxiliares

    private String statusDe(long id) {
        return jdbc.sql("SELECT status::text FROM ingestao_execucao WHERE id = :id")
            .param("id", id).query(String.class).single();
    }

    private String erroDe(long id) {
        return jdbc.sql("SELECT coalesce(erro, '') FROM ingestao_execucao WHERE id = :id")
            .param("id", id).query(String.class).single();
    }

    /** Um voto qualquer, só para ter o que alterar. */
    private long prepararVoto() {
        var politico = jdbc.sql("""
                INSERT INTO politico (nome_civil) VALUES ('Fulana de Teste') RETURNING id
                """).query(String.class).single();
        long proposicao = jdbc.sql("""
                INSERT INTO proposicao (casa, id_externo, sigla_tipo, numero, ano, ementa, url_tramitacao)
                VALUES ('CAMARA', 'p-' || gen_random_uuid(), 'PL', 1, 2023, 'Ementa', 'https://exemplo')
                RETURNING id
                """).query(Long.class).single();
        long votacao = jdbc.sql("""
                INSERT INTO votacao (casa, id_externo, proposicao_id, data_votacao, descricao, tipo, url_fonte)
                VALUES ('CAMARA', 'v-' || gen_random_uuid(), :prop, now(), 'Votacao', 'NOMINAL', 'https://exemplo')
                RETURNING id
                """).param("prop", proposicao).query(Long.class).single();
        return jdbc.sql("""
                INSERT INTO voto_nominal (votacao_id, politico_id, voto, voto_origem)
                VALUES (:votacao, :politico::uuid, 'SIM', 'Sim') RETURNING id
                """).param("votacao", votacao).param("politico", politico)
            .query(Long.class).single();
    }
}
