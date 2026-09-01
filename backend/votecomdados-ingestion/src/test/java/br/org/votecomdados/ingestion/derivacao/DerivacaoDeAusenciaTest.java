package br.org.votecomdados.ingestion.derivacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.org.votecomdados.core.dominio.Enums.CasaLegislativa;
import br.org.votecomdados.core.dominio.Enums.Fonte;
import br.org.votecomdados.core.dominio.Enums.TipoJob;
import br.org.votecomdados.ingestion.execucao.ControleDeExecucaoService;
import br.org.votecomdados.ingestion.execucao.Execucao;
import br.org.votecomdados.ingestion.massa.JobDeBackfillCamara;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;
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
 * O B8, fechado e verificável.
 *
 * <p>A Câmara publica só quem votou. Estes testes provam que o que falta é
 * calculado — e que licença não é confundida com falta, que é a diferença
 * entre informar e caluniar.
 */
@SpringBootTest
@Testcontainers
class DerivacaoDeAusenciaTest {

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
    private static final LocalDate DIA = LocalDate.of(2026, 3, 17);

    @Autowired DerivadorDeAusencia derivador;
    @Autowired JobDeBackfillCamara backfill;
    @Autowired ControleDeExecucaoService controle;
    @Autowired JdbcClient jdbc;

    Execucao execucao;
    long votacaoNominal;

    @BeforeEach
    void abrir() {
        execucao = controle.iniciar(Fonte.CAMARA, TipoJob.BACKFILL, "{}");
        votacaoNominal = criarVotacao("vot-nominal", "NOMINAL", DIA.atTime(23, 19));
    }

    @AfterEach
    void limpar() {
        controle.close();
        jdbc.sql("DELETE FROM politico").update();
        jdbc.sql("DELETE FROM votacao").update();
        jdbc.sql("DELETE FROM proposicao").update();
        jdbc.sql("DELETE FROM ingestao_execucao").update();
    }

    /** O caso central: estava em exercício, não votou, faltou. */
    @Test
    void quem_estava_em_exercicio_e_nao_votou_aparece_como_ausente() {
        var faltou = parlamentar("Quem Faltou", "EXERCICIO", "Exercício");

        var r = derivador.derivar(execucao, CasaLegislativa.CAMARA);

        assertThat(r.ausentes()).isEqualTo(1);
        assertThat(votoDe(faltou)).contains("AUSENTE");
        assertThat(origemDe(faltou)).contains("DERIVADO");
        assertThat(rotuloDe(faltou)).as("linha derivada nao tem rotulo de origem").isEmpty();
    }

    /**
     * A pergunta 10, respondida: exibir licença como falta atribuiria à pessoa
     * uma escolha que ela não fez.
     */
    @Test
    void quem_estava_licenciado_nao_vira_ausente() {
        var licenciado = parlamentar("Quem Estava De Licenca", "LICENCA", "Licença");

        var r = derivador.derivar(execucao, CasaLegislativa.CAMARA);

        assertThat(r.licenciados()).isEqualTo(1);
        assertThat(r.ausentes()).isZero();
        assertThat(votoDe(licenciado)).contains("LICENCIADO");
    }

    /** Não estar na Casa não é ausência: é não ser parlamentar naquele dia. */
    @Test
    void suplente_nao_convocado_nao_gera_linha_nenhuma() {
        var suplente = parlamentar("Suplente Sem Convocacao", "SUPLENCIA", "SUPLENCIA");

        derivador.derivar(execucao, CasaLegislativa.CAMARA);

        assertThat(votoDe(suplente)).isEmpty();
    }

    @Test
    void mandato_encerrado_tambem_nao_gera_linha() {
        var encerrado = parlamentar("Mandato Encerrado", "FIM_MANDATO", "FIM_MANDATO");

        derivador.derivar(execucao, CasaLegislativa.CAMARA);

        assertThat(votoDe(encerrado)).isEmpty();
    }

    @Test
    void quem_votou_mantem_o_voto_da_fonte() {
        var votou = parlamentar("Quem Votou", "EXERCICIO", "Exercício");
        jdbc.sql("""
                INSERT INTO voto_nominal (votacao_id, politico_id, voto, voto_origem,
                                          origem_registro)
                VALUES (:v, :p, 'SIM', 'Sim', 'FONTE')
                """).param("v", votacaoNominal).param("p", votou).update();

        derivador.derivar(execucao, CasaLegislativa.CAMARA);

        assertThat(votoDe(votou)).contains("SIM");
        assertThat(origemDe(votou)).contains("FONTE");
    }

    /** Votação simbólica não tem registro individual — nem da fonte, nem nosso. */
    @Test
    void votacao_simbolica_nao_recebe_derivacao() {
        long simbolica = criarVotacao("vot-simbolica", "SIMBOLICA", DIA.atTime(15, 0));
        parlamentar("Em Exercicio", "EXERCICIO", "Exercício");

        derivador.derivar(execucao, CasaLegislativa.CAMARA);

        assertThat(jdbc.sql("SELECT count(*) FROM voto_nominal WHERE votacao_id = :v")
            .param("v", simbolica).query(Long.class).single()).isZero();
    }

    /**
     * A sessão foi às 23:19 de 17/03 em Brasília — 02:19 de 18/03 em UTC. Se a
     * derivação usasse a data UTC, alguém cujo período terminou em 17/03
     * apareceria fora do universo, e a ausência sumiria.
     */
    @Test
    void a_data_usada_e_a_local_da_sessao_e_nao_a_utc() {
        var soAte17 = parlamentarComPeriodo("Saiu Em 17", "EXERCICIO", "Exercício",
                                            LocalDate.of(2026, 1, 1),
                                            LocalDate.of(2026, 3, 18));

        derivador.derivar(execucao, CasaLegislativa.CAMARA);

        assertThat(votoDe(soAte17))
            .as("em Brasilia a sessao foi dia 17, dentro do periodo")
            .contains("AUSENTE");
    }

    @Test
    void recalcular_nao_duplica_e_substitui_o_anterior() {
        parlamentar("Quem Faltou", "EXERCICIO", "Exercício");

        var primeira = derivador.derivar(execucao, CasaLegislativa.CAMARA);
        var segunda = derivador.derivar(execucao, CasaLegislativa.CAMARA);

        assertThat(segunda.ausentes()).isEqualTo(primeira.ausentes());
        assertThat(segunda.removidas()).isEqualTo(primeira.ausentes());
        assertThat(jdbc.sql("SELECT count(*) FROM voto_nominal").query(Long.class).single())
            .isEqualTo(1);
    }

    /**
     * Período corrigido retroativamente pela fonte: a linha derivada antiga
     * diria que a pessoa faltou a uma sessão de que nunca participou.
     */
    @Test
    void periodo_corrigido_na_origem_remove_a_ausencia_antiga() {
        var pessoa = parlamentar("Mandato Corrigido", "EXERCICIO", "Exercício");
        derivador.derivar(execucao, CasaLegislativa.CAMARA);
        assertThat(votoDe(pessoa)).contains("AUSENTE");

        // A Casa corrige: na verdade essa pessoa era suplente, não estava lá.
        jdbc.sql("UPDATE mandato_exercicio SET situacao = 'SUPLENCIA' WHERE politico_id = :p")
            .param("p", pessoa).update();
        derivador.derivar(execucao, CasaLegislativa.CAMARA);

        assertThat(votoDe(pessoa)).as("a ausencia precisa sumir junto").isEmpty();
    }

    /** Voto que chega atrasado tem de sobrescrever a ausência calculada. */
    @Test
    void voto_real_que_chega_depois_substitui_a_ausencia_derivada() {
        var pessoa = parlamentar("Voto Atrasado", "EXERCICIO", "Exercício");
        derivador.derivar(execucao, CasaLegislativa.CAMARA);
        assertThat(origemDe(pessoa)).contains("DERIVADO");

        jdbc.sql("""
                INSERT INTO voto_nominal (votacao_id, politico_id, voto, voto_origem,
                                          origem_registro)
                VALUES (:v, :p, 'NAO', 'Não', 'FONTE')
                ON CONFLICT (votacao_id, politico_id) DO UPDATE SET
                    voto = EXCLUDED.voto, voto_origem = EXCLUDED.voto_origem,
                    origem_registro = EXCLUDED.origem_registro
                """).param("v", votacaoNominal).param("p", pessoa).update();

        assertThat(votoDe(pessoa)).contains("NAO");
        assertThat(origemDe(pessoa)).contains("FONTE");
    }

    /** Derivar no Senado duplicaria o que a Casa já declara. */
    @Test
    void o_senado_e_recusado_em_vez_de_silenciosamente_ignorado() {
        assertThatThrownBy(() -> derivador.derivar(execucao, CasaLegislativa.SENADO))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("bancada inteira");
    }

    /**
     * Ponta a ponta com os arquivos reais: carrega a votação e seus 376 votos,
     * e um deputado da coorte que estava em exercício e NÃO consta do arquivo
     * precisa aparecer como ausente.
     */
    @Test
    void ponta_a_ponta_com_o_arquivo_real_da_camara() {
        jdbc.sql("DELETE FROM votacao").update();
        backfill.carregarVotacoes(execucao, GOLDEN.resolve("camara-votacoes-2026-amostra.csv"));

        var ausente = parlamentar("Quem Nao Aparece No Arquivo", "EXERCICIO", "Exercício");
        backfill.carregarVotos(execucao, GOLDEN.resolve("camara-votacoesVotos-2026-amostra.csv"));

        var r = derivador.derivar(execucao, CasaLegislativa.CAMARA);

        assertThat(r.ausentes())
            .as("a fonte nao publica ausencia; ela precisa ser calculada")
            .isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT voto::text FROM voto_nominal WHERE politico_id = :p
                """).param("p", ausente).query(String.class).single())
            .isEqualTo("AUSENTE");
    }

    // ---------------------------------------------------------------- auxiliares

    private Optional<String> votoDe(UUID politico) {
        return jdbc.sql("SELECT voto::text FROM voto_nominal WHERE politico_id = :p")
            .param("p", politico).query(String.class).optional();
    }

    private Optional<String> origemDe(UUID politico) {
        return jdbc.sql("SELECT origem_registro::text FROM voto_nominal WHERE politico_id = :p")
            .param("p", politico).query(String.class).optional();
    }

    private Optional<String> rotuloDe(UUID politico) {
        return jdbc.sql("SELECT voto_origem FROM voto_nominal WHERE politico_id = :p")
            .param("p", politico).query(String.class).optional();
    }

    private long criarVotacao(String idExterno, String tipo,
                              java.time.LocalDateTime quandoEmBrasilia) {
        return jdbc.sql("""
                INSERT INTO votacao (casa, id_externo, data_votacao, descricao, tipo,
                                     ambito, url_fonte)
                VALUES ('CAMARA', :id, :quando::timestamp AT TIME ZONE 'America/Sao_Paulo',
                        'Votacao de teste', :tipo::tipo_votacao_enum, 'PLENARIO',
                        'https://exemplo')
                RETURNING id
                """)
            .param("id", idExterno).param("tipo", tipo)
            .param("quando", quandoEmBrasilia.toString())
            .query(Long.class).single();
    }

    private UUID parlamentar(String nome, String situacao, String situacaoOrigem) {
        return parlamentarComPeriodo(nome, situacao, situacaoOrigem,
                                     LocalDate.of(2023, 2, 1), null);
    }

    private UUID parlamentarComPeriodo(String nome, String situacao, String situacaoOrigem,
                                       LocalDate inicio, LocalDate fim) {
        var id = jdbc.sql("INSERT INTO politico (nome_civil) VALUES (:n) RETURNING id")
            .param("n", nome).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO candidatura (politico_id, sq_candidato_tse, ano_eleicao, cargo,
                                         esfera, uf, partido_sigla, status)
                VALUES (:id, 'sq-' || gen_random_uuid(), 2026, 'DEPUTADO_FEDERAL',
                        'FEDERAL', 'SP', 'XYZ', 'DEFERIDO')
                """).param("id", id).update();
        jdbc.sql("""
                INSERT INTO mandato_exercicio (politico_id, casa, situacao, situacao_origem,
                                               inicio, fim)
                VALUES (:id, 'CAMARA', :sit::situacao_exercicio_enum, :origem, :inicio, :fim)
                """)
            .param("id", id).param("sit", situacao).param("origem", situacaoOrigem)
            .param("inicio", inicio).param("fim", fim).update();
        return id;
    }
}
