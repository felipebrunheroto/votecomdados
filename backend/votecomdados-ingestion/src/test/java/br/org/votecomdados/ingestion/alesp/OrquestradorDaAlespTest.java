package br.org.votecomdados.ingestion.alesp;

import static org.assertj.core.api.Assertions.assertThat;

import br.org.votecomdados.core.dominio.Enums.Fonte;
import br.org.votecomdados.core.dominio.Enums.TipoJob;
import br.org.votecomdados.ingestion.execucao.ControleDeExecucaoService;
import br.org.votecomdados.ingestion.execucao.Execucao;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * O ciclo inteiro da Alesp contra HTTP real: cadastro, proposituras, autoria,
 * votos — e a finalização, que é o achado A1 aplicado a esta fonte.
 *
 * <h2>Por que este teste existe além do {@code JobDaAlespTest}</h2>
 *
 * O outro teste exercita {@link JobDaAlesp} isoladamente, com os arquivos já
 * em disco. Nada provava que {@link OrquestradorDaAlesp} — o download, a
 * ordem de carga e o fim da ingestão — funciona ponta a ponta. E foi
 * exatamente aqui que o achado A1 se repetiu: a Alesp era a única das três
 * fontes que carregava dado e nunca chamava {@code FinalizadorDeIngestao}.
 * Sem este teste, a correção ficaria provada só por leitura de código.
 */
@SpringBootTest
@Testcontainers
class OrquestradorDaAlespTest {

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

    private static final DateTimeFormatter HTTP_DATA =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
            .withZone(ZoneOffset.UTC);
    private static final Instant MODIFICADO_EM = Instant.parse("2026-08-31T06:28:00Z");
    private static final Path GOLDEN = Path.of("..", "..", "db", "golden");

    /**
     * Enio Tatto: IdSPL 431 — o mesmo id que, no cadastro, colide com o
     * IdDeputado de Carlão Pignatari (ver {@code JobDaAlespTest}). Escolhido
     * de propósito: se a resolução de identidade regredisse para o campo
     * errado, este teste pegaria também.
     */
    private static final String NOME_ENIO_TATTO = "Enio Tatto";

    @Autowired OrquestradorDaAlesp orquestrador;
    @Autowired ControleDeExecucaoService controle;
    @Autowired JdbcClient jdbc;

    HttpServer servidor;
    OrquestradorDaAlesp.Enderecos enderecos;
    Execucao execucao;

    @BeforeEach
    void preparar() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servirArquivo("/deputados.xml", "alesp-deputados-amostra.xml");
        servirArquivo("/naturezasSpl.xml", "alesp-naturezas-amostra.xml");
        servirArquivo("/reunioes.xml", "alesp-reunioes-comissao-amostra.xml");
        servirArquivo("/votacoes.xml", "alesp-votacoes-comissao-amostra.xml");
        servirBytes("/proposituras.zip",
                    zipar("proposituras.xml", "alesp-proposituras-amostra.xml"));
        servirBytes("/documento_autor.zip",
                    zipar("documento_autor.xml", "alesp-documento-autor-amostra.xml"));
        servidor.start();

        String base = "http://127.0.0.1:" + servidor.getAddress().getPort();
        enderecos = new OrquestradorDaAlesp.Enderecos(
            URI.create(base + "/deputados.xml"), URI.create(base + "/naturezasSpl.xml"),
            URI.create(base + "/proposituras.zip"), URI.create(base + "/documento_autor.zip"),
            URI.create(base + "/reunioes.xml"), URI.create(base + "/votacoes.xml"));

        execucao = controle.iniciar(Fonte.ALESP, TipoJob.INCREMENTAL, "{}");
    }

    @AfterEach
    void limpar() {
        servidor.stop(0);
        controle.close();
        jdbc.sql("DELETE FROM politico").update();
        jdbc.sql("DELETE FROM votacao").update();
        jdbc.sql("DELETE FROM proposicao").update();
        jdbc.sql("DELETE FROM staging.payload_bruto").update();
        jdbc.sql("DELETE FROM staging.registro_rejeitado").update();
        jdbc.sql("DELETE FROM ingestao_execucao").update();
    }

    @Test
    void ciclo_completo_resolve_carrega_e_fecha_a_ingestao(@TempDir Path dir) {
        naCoorte(NOME_ENIO_TATTO);

        var r = orquestrador.executar(execucao, dir, null, enderecos);

        assertThat(r.houveMudanca()).isTrue();
        assertThat(r.proposituras()).isPositive();
        assertThat(r.votacoes()).isPositive();
        assertThat(r.votos()).isPositive();

        var politicoId = jdbc.sql("SELECT id FROM politico WHERE nome_civil = :n")
            .param("n", NOME_ENIO_TATTO).query(UUID.class).single();

        // O vínculo saiu da resolução de identidade REAL (nome parlamentar
        // contra nome_urna), não de um insert direto em identificador_externo.
        assertThat(jdbc.sql("""
                SELECT metodo_resolucao::text FROM identificador_externo
                 WHERE sistema = 'ALESP' AND politico_id = :id
                """).param("id", politicoId).query(String.class).single())
            .isEqualTo("FUZZY");

        assertThat(jdbc.sql("""
                SELECT count(*) FROM voto_nominal vn
                  JOIN votacao v ON v.id = vn.votacao_id
                 WHERE vn.politico_id = :id AND v.casa = 'ALESP'
                """).param("id", politicoId).query(Long.class).single()).isPositive();

        // O que este teste existe para provar: a finalização rodou.
        assertThat(jdbc.sql("SELECT possui_atuacao_legislativa FROM politico WHERE id = :id")
            .param("id", politicoId).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("""
                SELECT possui_atuacao_legislativa FROM perfil_leitura WHERE politico_id = :id
                """).param("id", politicoId).query(Boolean.class).single()).isTrue();
    }

    @Test
    void ciclo_seguinte_sem_mudanca_nao_finaliza_de_novo(@TempDir Path dir) {
        naCoorte(NOME_ENIO_TATTO);
        var r1 = orquestrador.executar(execucao, dir, null, enderecos);
        controle.concluir(execucao, r1.watermarkNovo(), r1.votos(), 0);

        var proxima = controle.iniciar(Fonte.ALESP, TipoJob.INCREMENTAL, "{}");
        var r2 = orquestrador.executar(proxima, dir, MODIFICADO_EM, enderecos);

        assertThat(r2.houveMudanca()).isFalse();
        controle.concluir(proxima, MODIFICADO_EM, 0, 0);
    }

    // ---------------------------------------------------------------- auxiliares

    private void servirArquivo(String caminho, String arquivo) {
        try {
            servirBytes(caminho, Files.readAllBytes(GOLDEN.resolve(arquivo)));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void servirBytes(String caminho, byte[] corpo) {
        servidor.createContext(caminho, troca -> {
            String desde = troca.getRequestHeaders().getFirst("If-Modified-Since");
            troca.getResponseHeaders().add("Last-Modified", HTTP_DATA.format(MODIFICADO_EM));
            if (desde != null
                && !Instant.from(HTTP_DATA.parse(desde)).isBefore(MODIFICADO_EM)) {
                troca.sendResponseHeaders(304, -1);
                troca.close();
                return;
            }
            troca.sendResponseHeaders(200, corpo.length);
            try (var saida = troca.getResponseBody()) {
                saida.write(corpo);
            }
        });
    }

    /**
     * A Alesp publica proposituras e autoria dentro de zip. O golden file é o
     * XML solto (assim como {@code JobDaAlespTest} o lê); aqui ele é
     * compactado na hora para o servidor de teste se comportar como a fonte
     * real, sem duplicar o arquivo no repositório.
     */
    private byte[] zipar(String nomeDaEntrada, String golden) throws IOException {
        var saida = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(saida)) {
            zip.putNextEntry(new ZipEntry(nomeDaEntrada));
            zip.write(Files.readAllBytes(GOLDEN.resolve(golden)));
            zip.closeEntry();
        }
        return saida.toByteArray();
    }

    /**
     * Coloca uma pessoa da coorte com nome igual ao {@code NomeParlamentar} do
     * golden file, para a resolução de identidade casar de verdade — e não
     * por atalho de teste.
     */
    private void naCoorte(String nomeParlamentar) {
        var id = jdbc.sql("""
                INSERT INTO politico (nome_civil, nome_urna) VALUES (:n, :n) RETURNING id
                """).param("n", nomeParlamentar).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO candidatura (politico_id, sq_candidato_tse, ano_eleicao, turno,
                                         cargo, esfera, uf, partido_sigla, status)
                VALUES (:id, 'sq-' || gen_random_uuid(), 2026, 1, 'DEPUTADO_ESTADUAL',
                        'ESTADUAL', 'SP', 'XYZ', 'NAO_INFORMADO')
                """).param("id", id).update();
    }
}
