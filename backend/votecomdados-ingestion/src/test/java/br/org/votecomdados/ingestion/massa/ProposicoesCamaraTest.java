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
 * Proposições, temas e autoria dos arquivos reais — e o A5 fechado.
 *
 * <p>A amostra é coerente entre os três arquivos: as mesmas proposições, seus
 * temas e seus autores, incluindo um autor <b>sem</b> id de deputado (senador),
 * que é o caso do coautor sem perfil.
 */
@SpringBootTest
@Testcontainers
class ProposicoesCamaraTest {

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
    private static final Path PROPOSICOES = GOLDEN.resolve("camara-proposicoes-2026-amostra.csv");
    private static final Path TEMAS = GOLDEN.resolve("camara-proposicoesTemas-2026-amostra.csv");
    private static final Path AUTORES =
        GOLDEN.resolve("camara-proposicoesAutores-2026-amostra.csv");

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
        jdbc.sql("DELETE FROM proposicao").update();
        jdbc.sql("DELETE FROM ingestao_execucao").update();
    }

    @Test
    void so_entram_materias_com_autoria_na_coorte() {
        var r = job.carregarProposicoes(execucao, PROPOSICOES, TEMAS, AUTORES);

        assertThat(r.materias())
            .as("sem ninguem da coorte, nenhuma materia interessa").isZero();

        deputadosDosArquivosNaCoorte();
        var comCoorte = job.carregarProposicoes(execucao, PROPOSICOES, TEMAS, AUTORES);

        assertThat(comCoorte.materias()).isPositive();
    }

    /** O `tema` do B3: estava no schema, na API e na UI, e nada o populava. */
    @Test
    void o_tema_finalmente_tem_fonte() {
        deputadosDosArquivosNaCoorte();

        var r = job.carregarProposicoes(execucao, PROPOSICOES, TEMAS, AUTORES);

        assertThat(r.temas()).isPositive();
        var temas = jdbc.sql("SELECT DISTINCT tema FROM proposicao_tema ORDER BY 1")
            .query(String.class).list();
        assertThat(temas).isNotEmpty();
    }

    /** O arquivo de temas não traz o id: só a URI. */
    @Test
    void o_tema_e_ligado_pela_uri_porque_a_fonte_nao_publica_o_id() throws IOException {
        var cabecalho = Files.readAllLines(TEMAS, StandardCharsets.UTF_8).getFirst();
        assertThat(cabecalho).doesNotContain("idProposicao").contains("uriProposicao");

        deputadosDosArquivosNaCoorte();
        job.carregarProposicoes(execucao, PROPOSICOES, TEMAS, AUTORES);

        assertThat(jdbc.sql("SELECT count(*) FROM proposicao_tema")
            .query(Long.class).single()).isPositive();
    }

    /** Uma matéria com mais de um tema — a relação N:N que o B3 apontou. */
    @Test
    void uma_materia_pode_ter_varios_temas() {
        deputadosDosArquivosNaCoorte();
        job.carregarProposicoes(execucao, PROPOSICOES, TEMAS, AUTORES);

        var maximo = jdbc.sql("""
                SELECT max(quantos) FROM (
                    SELECT count(*) AS quantos FROM proposicao_tema GROUP BY proposicao_id
                ) x
                """).query(Long.class).single();

        assertThat(maximo).isGreaterThan(1);
    }

    /**
     * A lista de autoria de uma matéria vai completa; só quem é candidato em
     * 2026 ganha perfil. Omitir o coautor distorceria o registro; dar-lhe
     * página seria criar dossiê de quem não se apresentou ao eleitorado.
     *
     * <p>Observação do dado real: em 2026 <b>nenhuma</b> matéria da Câmara
     * mistura autor deputado com não-deputado — quando o autor é senador,
     * órgão ou o Executivo, todos os autores são assim. Então o coautor sem
     * perfil é, na prática, um <i>deputado</i> que não se recandidatou. É esse
     * o cenário montado aqui.
     */
    @Test
    void coautor_fora_da_coorte_vira_nome_sem_perfil() {
        var todos = deputadosDosArquivos();
        assertThat(todos).as("a amostra precisa ter coautoria").hasSizeGreaterThan(1);
        // Todos menos um entram na coorte; o que sobra é o coautor sem perfil.
        colocarNaCoorte(todos.subList(0, todos.size() - 1));

        job.carregarProposicoes(execucao, PROPOSICOES, TEMAS, AUTORES);

        var semPerfil = jdbc.sql("""
                SELECT count(*) FROM proposicao_autor WHERE politico_id IS NULL
                """).query(Long.class).single();
        var comPerfil = jdbc.sql("""
                SELECT count(*) FROM proposicao_autor WHERE politico_id IS NOT NULL
                """).query(Long.class).single();

        assertThat(semPerfil).as("o coautor continua listado, so que sem pagina")
            .isPositive();
        assertThat(comPerfil).isPositive();
    }

    /**
     * O A5: a versão anterior nunca corrigia a ementa, e a plataforma exibiria
     * a versão errada para sempre.
     */
    @Test
    void ementa_corrigida_na_origem_chega_a_plataforma() {
        deputadosDosArquivosNaCoorte();
        job.carregarProposicoes(execucao, PROPOSICOES, TEMAS, AUTORES);

        var original = jdbc.sql("SELECT ementa FROM proposicao ORDER BY id LIMIT 1")
            .query(String.class).single();
        jdbc.sql("UPDATE proposicao SET ementa = 'texto errado que ficou colado'")
            .update();

        job.carregarProposicoes(execucao, PROPOSICOES, TEMAS, AUTORES);

        assertThat(jdbc.sql("SELECT ementa FROM proposicao ORDER BY id LIMIT 1")
            .query(String.class).single()).isEqualTo(original);
    }

    /** E atualizar não pode virar perder: o histórico guarda o que estava lá. */
    @Test
    void a_correcao_de_ementa_fica_registrada_no_historico() {
        deputadosDosArquivosNaCoorte();
        job.carregarProposicoes(execucao, PROPOSICOES, TEMAS, AUTORES);
        jdbc.sql("UPDATE proposicao SET ementa = 'versao intermediaria'").update();

        job.carregarProposicoes(execucao, PROPOSICOES, TEMAS, AUTORES);

        var registros = jdbc.sql("""
                SELECT count(*) FROM proposicao_historico WHERE campo = 'ementa'
                """).query(Long.class).single();
        assertThat(registros).isGreaterThanOrEqualTo(2);

        var doWorker = jdbc.sql("""
                SELECT count(*) FROM proposicao_historico
                 WHERE campo = 'ementa' AND execucao_id = :e
                """).param("e", execucao.id()).query(Long.class).single();
        assertThat(doWorker).as("a alteracao do worker fica atribuida a ele").isPositive();
    }

    /** Tema retirado na origem precisa sumir daqui também. */
    @Test
    void tema_removido_na_origem_e_removido_aqui() {
        deputadosDosArquivosNaCoorte();
        job.carregarProposicoes(execucao, PROPOSICOES, TEMAS, AUTORES);
        long antes = contar("proposicao_tema");

        jdbc.sql("""
                INSERT INTO proposicao_tema (proposicao_id, tema)
                SELECT id, 'Tema Que Nao Existe Mais' FROM proposicao ORDER BY id LIMIT 1
                """).update();
        assertThat(contar("proposicao_tema")).isEqualTo(antes + 1);

        job.carregarProposicoes(execucao, PROPOSICOES, TEMAS, AUTORES);

        assertThat(contar("proposicao_tema")).isEqualTo(antes);
    }

    @Test
    void recarregar_nao_duplica_materia_tema_nem_autoria() {
        deputadosDosArquivosNaCoorte();
        job.carregarProposicoes(execucao, PROPOSICOES, TEMAS, AUTORES);
        var antes = List.of(contar("proposicao"), contar("proposicao_tema"),
                            contar("proposicao_autor"));

        job.carregarProposicoes(execucao, PROPOSICOES, TEMAS, AUTORES);

        assertThat(List.of(contar("proposicao"), contar("proposicao_tema"),
                           contar("proposicao_autor"))).isEqualTo(antes);
    }

    @Test
    void materia_sem_ementa_nao_entra_com_texto_inventado() {
        deputadosDosArquivosNaCoorte();
        job.carregarProposicoes(execucao, PROPOSICOES, TEMAS, AUTORES);

        assertThat(jdbc.sql("""
                SELECT count(*) FROM proposicao WHERE ementa IS NULL OR btrim(ementa) = ''
                """).query(Long.class).single()).isZero();
    }

    // ---------------------------------------------------------------- auxiliares

    private long contar(String tabela) {
        return jdbc.sql("SELECT count(*) FROM " + tabela).query(Long.class).single();
    }

    private void deputadosDosArquivosNaCoorte() {
        colocarNaCoorte(deputadosDosArquivos());
    }

    /** Ids de deputado que assinam as matérias da amostra, em ordem estável. */
    private List<String> deputadosDosArquivos() {
        try {
            var linhas = Files.readAllLines(AUTORES, StandardCharsets.UTF_8);
            var cabecalho = List.of(linhas.getFirst().replace("﻿", "").split(";"));
            int coluna = cabecalho.indexOf("\"idDeputadoAutor\"");
            var ids = new java.util.ArrayList<String>();
            for (String linha : linhas.subList(1, linhas.size())) {
                var campos = linha.split(";");
                if (coluna >= campos.length) continue;
                String id = campos[coluna].replace("\"", "").trim();
                if (!id.isEmpty() && !ids.contains(id)) ids.add(id);
            }
            return ids;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void colocarNaCoorte(List<String> idsDeDeputado) {
        for (String id : idsDeDeputado) {
            UUID politico = jdbc.sql("""
                    INSERT INTO politico (nome_civil) VALUES ('Autor ' || :id)
                    RETURNING id
                    """).param("id", id).query(UUID.class).single();
            jdbc.sql("""
                    INSERT INTO candidatura (politico_id, sq_candidato_tse, ano_eleicao,
                                             cargo, esfera, uf, partido_sigla, status)
                    VALUES (:p, 'sq-' || :id, 2026, 'DEPUTADO_FEDERAL', 'FEDERAL',
                            'SP', 'XYZ', 'DEFERIDO')
                    """).param("p", politico).param("id", id).update();
            jdbc.sql("""
                    INSERT INTO identificador_externo (politico_id, sistema, identificador)
                    VALUES (:p, 'CAMARA', :id)
                    """).param("p", politico).param("id", id).update();
        }
    }
}
