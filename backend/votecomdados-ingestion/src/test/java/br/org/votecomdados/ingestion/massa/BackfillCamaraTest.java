package br.org.votecomdados.ingestion.massa;

import static org.assertj.core.api.Assertions.assertThat;

import br.org.votecomdados.core.dominio.Enums.Fonte;
import br.org.votecomdados.core.dominio.Enums.TipoJob;
import br.org.votecomdados.ingestion.execucao.ControleDeExecucaoService;
import br.org.votecomdados.ingestion.execucao.Execucao;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
 * A carga em massa contra os arquivos reais da Câmara.
 *
 * <p>A amostra é coerente: uma votação de plenário com <b>todos os seus 376
 * votos</b> — cobrindo os cinco rótulos que a Casa emite — e uma votação sem
 * voto nominal. Amostra incoerente (votos de votações ausentes) faria o teste
 * passar por acidente.
 */
@SpringBootTest
@Testcontainers
class BackfillCamaraTest {

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

    private static final Path GOLDEN = Path.of("..", "..", "db", "golden");
    private static final Path VOTACOES = GOLDEN.resolve("camara-votacoes-2026-amostra.csv");
    private static final Path VOTOS =
        GOLDEN.resolve("camara-votacoesVotos-2026-amostra.csv");

    /** A votação nominal da amostra, e um deputado que votou nela. */
    private static final String VOTACAO_NOMINAL = "2563330-59";

    @Autowired JobDeBackfillCamara job;
    @Autowired ControleDeExecucaoService controle;
    @Autowired JdbcClient jdbc;

    Execucao execucao;

    @BeforeEach
    void abrir() {
        execucao = controle.iniciar(Fonte.CAMARA, TipoJob.BACKFILL, "{\"ano\": 2026}");
    }

    @AfterEach
    void limpar() {
        controle.close();
        jdbc.sql("DELETE FROM politico").update();
        jdbc.sql("DELETE FROM votacao").update();
        jdbc.sql("DELETE FROM proposicao").update();
        jdbc.sql("DELETE FROM staging.registro_rejeitado").update();
        jdbc.sql("DELETE FROM ingestao_execucao").update();
    }

    @Test
    void carrega_votacoes_do_arquivo_real() {
        int gravadas = job.carregarVotacoes(execucao, VOTACOES);

        assertThat(gravadas).isEqualTo(2);
        assertThat(jdbc.sql("SELECT ambito::text FROM votacao WHERE id_externo = :id")
            .param("id", VOTACAO_NOMINAL).query(String.class).single())
            .isEqualTo("PLENARIO");
    }

    /**
     * O horário da Câmara é de Brasília. Gravado como UTC, uma votação das
     * 23:19 de 17/03 viraria 18/03 — data errada numa plataforma factual.
     */
    @Test
    void o_horario_e_interpretado_como_brasilia_e_nao_como_utc() {
        job.carregarVotacoes(execucao, VOTACOES);

        var dataLocal = jdbc.sql("""
                SELECT to_char(data_votacao AT TIME ZONE 'America/Sao_Paulo',
                               'YYYY-MM-DD HH24:MI')
                  FROM votacao WHERE id_externo = :id
                """).param("id", VOTACAO_NOMINAL).query(String.class).single();

        assertThat(dataLocal).isEqualTo("2026-03-17 23:19");
    }

    /**
     * Na linha real, "Outros" é 21 enquanto a descrição diz "Abstenção: 3". O
     * campo agrupa abstenção, obstrução e Art. 17 — mapeá-lo para abstenção
     * publicaria um número errado com cara de certo.
     */
    @Test
    void votos_outros_nao_vira_placar_de_abstencao() {
        job.carregarVotacoes(execucao, VOTACOES);

        var linha = jdbc.sql("""
                SELECT placar_sim, placar_nao, placar_abstencao FROM votacao
                 WHERE id_externo = :id
                """).param("id", VOTACAO_NOMINAL)
            .query((rs, n) -> List.of(
                String.valueOf(rs.getObject("placar_sim")),
                String.valueOf(rs.getObject("placar_nao")),
                String.valueOf(rs.getObject("placar_abstencao"))))
            .single();

        assertThat(linha.get(0)).isEqualTo("97");
        assertThat(linha.get(1)).isEqualTo("258");
        assertThat(linha.get(2)).as("a fonte nao publica abstencao separada").isEqualTo("null");
    }

    /** `0` é sentinela de ausência; tratado como id, derrubaria a carga por FK. */
    @Test
    void proposicao_zero_e_ausencia_e_nao_um_id() {
        job.carregarVotacoes(execucao, VOTACOES);

        assertThat(jdbc.sql("SELECT count(*) FROM votacao WHERE proposicao_id IS NOT NULL")
            .query(Long.class).single()).isZero();
    }

    @Test
    void carrega_apenas_os_votos_de_quem_esta_na_coorte() {
        var doisDaCoorte = deputadosDaAmostraNaCoorte(2);

        var r = job.carregarVotacoes(execucao, VOTACOES);
        var votos = job.carregarVotos(execucao, VOTOS);

        assertThat(r).isEqualTo(2);
        assertThat(votos.gravados())
            .as("os outros ~374 nao sao candidatos em 2026")
            .isEqualTo(doisDaCoorte.size());
        assertThat(votos.rotulosSemTraducao()).isEmpty();
    }

    /**
     * A distinção do Q3 acontecendo na prática: 374 parlamentares fora da
     * coorte não podem virar 374 linhas de quarentena por votação.
     */
    @Test
    void quem_esta_fora_da_coorte_nao_gera_quarentena_por_voto() {
        deputadosDaAmostraNaCoorte(1);
        job.carregarVotacoes(execucao, VOTACOES);
        job.carregarVotos(execucao, VOTOS);

        assertThat(jdbc.sql("SELECT count(*) FROM staging.registro_rejeitado")
            .query(Long.class).single())
            .as("eles ja foram contados uma vez, no cadastro")
            .isZero();
    }

    @Test
    void o_rotulo_original_da_fonte_e_preservado_ao_lado_do_enum() {
        deputadosDaAmostraNaCoorte(30);
        job.carregarVotacoes(execucao, VOTACOES);
        job.carregarVotos(execucao, VOTOS);

        var pares = jdbc.sql("""
                SELECT DISTINCT voto::text || '|' || voto_origem FROM voto_nominal
                 ORDER BY 1
                """).query(String.class).list();

        assertThat(pares).isNotEmpty();
        assertThat(pares).allSatisfy(p ->
            assertThat(p).doesNotStartWith("null"));
        assertThat(jdbc.sql("""
                SELECT count(*) FROM voto_nominal WHERE origem_registro <> 'FONTE'
                """).query(Long.class).single())
            .as("nada aqui e derivado: tudo veio publicado pela Casa").isZero();
    }

    /** Votação sem voto individual é simbólica — é a definição da arquitetura. */
    @Test
    void votacao_sem_voto_nominal_continua_simbolica() {
        deputadosDaAmostraNaCoorte(5);
        job.carregarVotacoes(execucao, VOTACOES);
        job.carregarVotos(execucao, VOTOS);

        var tipos = jdbc.sql("""
                SELECT id_externo || '=' || tipo::text FROM votacao ORDER BY 1
                """).query(String.class).list();

        assertThat(tipos).contains(VOTACAO_NOMINAL + "=NOMINAL");
        assertThat(tipos).anySatisfy(t -> assertThat(t).endsWith("=SIMBOLICA"));
    }

    @Test
    void recarregar_o_mesmo_arquivo_nao_duplica_nada() {
        deputadosDaAmostraNaCoorte(10);

        job.carregarVotacoes(execucao, VOTACOES);
        job.carregarVotos(execucao, VOTOS);
        long votacoes = contar("votacao");
        long votos = contar("voto_nominal");

        job.carregarVotacoes(execucao, VOTACOES);
        job.carregarVotos(execucao, VOTOS);

        assertThat(contar("votacao")).isEqualTo(votacoes);
        assertThat(contar("voto_nominal")).isEqualTo(votos);
    }

    /** A alteração feita pelo worker precisa ficar atribuída a ele. */
    @Test
    void alteracao_de_voto_pelo_backfill_fica_atribuida_a_execucao() {
        var ids = deputadosDaAmostraNaCoorte(1);
        job.carregarVotacoes(execucao, VOTACOES);
        job.carregarVotos(execucao, VOTOS);

        jdbc.sql("UPDATE voto_nominal SET voto = 'ABSTENCAO', voto_origem = 'Abstenção'")
            .update();
        job.carregarVotos(execucao, VOTOS);

        var atribuidas = jdbc.sql("""
                SELECT count(*) FROM voto_nominal_historico WHERE execucao_id = :id
                """).param("id", execucao.id()).query(Long.class).single();

        assertThat(ids).hasSize(1);
        assertThat(atribuidas).isPositive();
    }

    // ---------------------------------------------------------------- auxiliares

    private long contar(String tabela) {
        return jdbc.sql("SELECT count(*) FROM " + tabela).query(Long.class).single();
    }

    /** Põe os primeiros N deputados da amostra de votos dentro da coorte. */
    private List<String> deputadosDaAmostraNaCoorte(int quantos) {
        var ids = new ArrayList<String>();
        try {
            var linhas = Files.readAllLines(VOTOS, StandardCharsets.UTF_8);
            var cabecalho = List.of(linhas.getFirst().replace("﻿", "").split(";"));
            int coluna = cabecalho.indexOf("\"deputado_id\"");
            for (String linha : linhas.subList(1, linhas.size())) {
                String id = linha.split(";")[coluna].replace("\"", "");
                if (!ids.contains(id)) ids.add(id);
                if (ids.size() == quantos) break;
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        for (String id : ids) {
            UUID politico = jdbc.sql("""
                    INSERT INTO politico (nome_civil) VALUES ('Deputado ' || :id)
                    RETURNING id
                    """).param("id", id).query(UUID.class).single();
            jdbc.sql("""
                    INSERT INTO candidatura (politico_id, sq_candidato_tse, ano_eleicao,
                                             cargo, esfera, uf, partido_sigla, status)
                    VALUES (:p, 'sq-' || :id, 2026, 'DEPUTADO_FEDERAL', 'FEDERAL', 'SP',
                            'XYZ', 'DEFERIDO')
                    """).param("p", politico).param("id", id).update();
            jdbc.sql("""
                    INSERT INTO identificador_externo (politico_id, sistema, identificador)
                    VALUES (:p, 'CAMARA', :id)
                    """).param("p", politico).param("id", id).update();
        }
        return ids;
    }
}
