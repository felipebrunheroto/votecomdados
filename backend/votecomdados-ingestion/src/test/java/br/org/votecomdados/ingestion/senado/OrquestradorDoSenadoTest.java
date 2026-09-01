package br.org.votecomdados.ingestion.senado;

import static org.assertj.core.api.Assertions.assertThat;

import br.org.votecomdados.core.dominio.Enums.Fonte;
import br.org.votecomdados.core.dominio.Enums.TipoJob;
import br.org.votecomdados.ingestion.execucao.ControleDeExecucaoService;
import br.org.votecomdados.ingestion.execucao.Execucao;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * O ciclo do Senado contra HTTP real, ponta a ponta: universo, detalhe,
 * cadastro e votações — a peça que faltava para {@link JobDoSenado} deixar de
 * ser código morto.
 *
 * <h2>Por que Alan Rick, e não um nome qualquer</h2>
 *
 * O código 5672 vota nas quatro votações de {@code senado-votacao-amostra.jsonl}
 * (usadas por {@link JobDoSenadoTest}) — verificado ao montar este teste. Ele
 * também está no golden de universo/detalhe deste teste. É a mesma coerência
 * que {@code db/golden/README.md} exige da Câmara: a amostra prova o ciclo
 * inteiro, não pedaços que nunca se encontrariam em produção.
 */
@SpringBootTest
@TestPropertySource(properties = "votecomdados.identidade.limiar-similaridade=0.85")
@Testcontainers
class OrquestradorDoSenadoTest {

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
    private static final String ALAN_RICK_NOME = "Alan Rick Miranda";
    private static final LocalDate ALAN_RICK_NASCIMENTO = LocalDate.of(1976, 10, 23);

    @Autowired OrquestradorDoSenado orquestrador;
    @Autowired ControleDeExecucaoService controle;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    HttpServer servidor;
    EnderecosDoSenado enderecos;
    Execucao execucao;

    @BeforeEach
    void preparar() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servirArquivo("/senador/lista/legislatura/50/57",
                      "senado-parlamentares-lista-amostra.json");
        servirDetalhesPorCodigo();
        servirVotacoesDoAno();
        servidor.start();

        enderecos = EnderecosDoSenado.comBase("http://127.0.0.1:" + porta());
        execucao = controle.iniciar(Fonte.SENADO, TipoJob.INCREMENTAL, "{}");
    }

    @AfterEach
    void limpar() {
        servidor.stop(0);
        controle.close();
        jdbc.sql("DELETE FROM politico").update();
        jdbc.sql("DELETE FROM votacao").update();
        jdbc.sql("DELETE FROM staging.payload_bruto").update();
        jdbc.sql("DELETE FROM staging.registro_rejeitado").update();
        jdbc.sql("DELETE FROM ingestao_execucao").update();
    }

    @Test
    void ciclo_completo_resolve_deterministico_carrega_e_fecha_a_ingestao() {
        naCoorte(ALAN_RICK_NOME, ALAN_RICK_NASCIMENTO);

        var r = orquestrador.executar(execucao, 2026, null, enderecos);

        assertThat(r.houveMudanca()).isTrue();
        assertThat(r.vinculosResolvidos()).isEqualTo(1);
        assertThat(r.votacoes()).isEqualTo(4);
        assertThat(r.votos()).isPositive();

        var politicoId = jdbc.sql("SELECT id FROM politico WHERE nome_civil = :n")
            .param("n", ALAN_RICK_NOME).query(UUID.class).single();

        // Nome completo + data de nascimento: o Senado permite determinístico,
        // ao contrário da Alesp — não deveria cair em FUZZY.
        assertThat(jdbc.sql("""
                SELECT metodo_resolucao::text FROM identificador_externo
                 WHERE sistema = 'SENADO' AND politico_id = :id
                """).param("id", politicoId).query(String.class).single())
            .isEqualTo("DETERMINISTICO");

        assertThat(jdbc.sql("""
                SELECT count(*) FROM voto_nominal vn
                  JOIN votacao v ON v.id = vn.votacao_id
                 WHERE vn.politico_id = :id AND v.casa = 'SENADO'
                """).param("id", politicoId).query(Long.class).single()).isEqualTo(4);

        // O que este teste existe para provar: a finalizacao rodou.
        assertThat(jdbc.sql("SELECT possui_atuacao_legislativa FROM politico WHERE id = :id")
            .param("id", politicoId).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("""
                SELECT possui_atuacao_legislativa FROM perfil_leitura WHERE politico_id = :id
                """).param("id", politicoId).query(Boolean.class).single()).isTrue();
    }

    /** Watermark = maior dataSessao vista, nao o relogio da coleta. */
    @Test
    void watermark_e_a_maior_data_de_sessao_vista() {
        naCoorte(ALAN_RICK_NOME, ALAN_RICK_NASCIMENTO);

        var r = orquestrador.executar(execucao, 2026, null, enderecos);

        assertThat(r.watermarkNovo())
            .isEqualTo(LocalDate.of(2026, 6, 16).atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
    }

    /** Nao mover o marcador sobre janela sem novidade: mesma regra do incremental. */
    @Test
    void sem_sessao_mais_nova_que_o_watermark_nao_move_o_marcador() {
        naCoorte(ALAN_RICK_NOME, ALAN_RICK_NASCIMENTO);
        var jaConhecido = LocalDate.of(2026, 6, 16)
            .atStartOfDay(java.time.ZoneOffset.UTC).toInstant();

        var r = orquestrador.executar(execucao, 2026, jaConhecido, enderecos);

        assertThat(r.houveMudanca()).isFalse();
        assertThat(r.watermarkNovo()).isEqualTo(jaConhecido);
    }

    /**
     * A economia que evita ~900 chamadas HTTP por dia: quem já tem vínculo
     * não tem o detalhe rebuscado. Prova indireta — derruba o servidor de
     * detalhe e mostra que o segundo ciclo não precisa dele.
     */
    @Test
    void segundo_ciclo_nao_rebusca_detalhe_de_quem_ja_foi_resolvido() {
        naCoorte(ALAN_RICK_NOME, ALAN_RICK_NASCIMENTO);
        orquestrador.executar(execucao, 2026, null, enderecos);
        controle.close();

        servidor.removeContext("/senador/5672");
        servidor.removeContext("/senador/89");

        var proxima = controle.iniciar(Fonte.SENADO, TipoJob.INCREMENTAL, "{}");
        var r2 = orquestrador.executar(proxima, 2026,
            LocalDate.of(2020, 1, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
            enderecos);

        // Não lançou exceção mesmo com o endpoint de detalhe fora do ar — e é
        // isso que a asserção abaixo prova: NADA foi reprocessado (0, não 1),
        // porque os dois já estavam resolvidos/rejeitados e nenhum precisou
        // do detalhe. O vínculo do primeiro ciclo continua de pé.
        assertThat(r2.vinculosResolvidos()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM identificador_externo WHERE sistema = 'SENADO'")
            .query(Long.class).single()).isEqualTo(1);
    }

    /**
     * O cadastro do Senado traz e-mail, telefone e foto para quem está em
     * exercício (verificado em 01/09/2026). Nenhum tem uso na resolução de
     * identidade, e nenhum pode chegar ao staging — é o argumento da
     * allowlist em forma de teste, mesmo caso da Alesp no W12.
     */
    @Test
    void dado_pessoal_do_cadastro_nao_chega_ao_staging() {
        naCoorte(ALAN_RICK_NOME, ALAN_RICK_NASCIMENTO);

        orquestrador.executar(execucao, 2026, null, enderecos);

        var payloads = jdbc.sql("""
                SELECT payload::text FROM staging.payload_bruto
                 WHERE fonte = 'SENADO' AND recurso = 'parlamentar'
                """).query(String.class).list();

        assertThat(payloads).isNotEmpty();
        assertThat(payloads).noneMatch(p -> p.contains("Email")
                                         || p.contains("Telefone")
                                         || p.contains("UrlFoto")
                                         || p.contains("Endereco"));
        assertThat(payloads).allMatch(p -> p.contains("codigoParlamentar"));
    }

    // ---------------------------------------------------------------- auxiliares

    private void naCoorte(String nomeCivil, LocalDate nascimento) {
        var id = jdbc.sql("""
                INSERT INTO politico (nome_civil, nome_urna, data_nascimento)
                VALUES (:n, :n, :d) RETURNING id
                """).param("n", nomeCivil).param("d", nascimento).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO candidatura (politico_id, sq_candidato_tse, ano_eleicao, turno,
                                         cargo, esfera, uf, partido_sigla, status)
                VALUES (:id, 'sq-' || gen_random_uuid(), 2026, 1, 'SENADOR', 'FEDERAL',
                        'AC', 'XYZ', 'NAO_INFORMADO')
                """).param("id", id).update();
    }

    private void servirDetalhesPorCodigo() throws IOException {
        for (String linha : Files.readAllLines(
                GOLDEN.resolve("senado-parlamentares-detalhe-amostra.jsonl"),
                StandardCharsets.UTF_8)) {
            if (linha.isBlank()) continue;
            JsonNode no = json.readTree(linha);
            String codigo = no.path("DetalheParlamentar").path("Parlamentar")
                              .path("IdentificacaoParlamentar").path("CodigoParlamentar")
                              .asString();
            servirBytes("/senador/" + codigo, linha.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** As 4 votações da amostra do Senado, independente do ano pedido. */
    private void servirVotacoesDoAno() throws IOException {
        ArrayNode array = json.createArrayNode();
        for (String linha : Files.readAllLines(
                GOLDEN.resolve("senado-votacao-amostra.jsonl"), StandardCharsets.UTF_8)) {
            if (!linha.isBlank()) array.add(json.readTree(linha));
        }
        byte[] corpo = json.writeValueAsBytes(array);
        servidor.createContext("/votacao", troca -> {
            troca.getResponseHeaders().add("Content-Type", "application/json");
            troca.sendResponseHeaders(200, corpo.length);
            try (var saida = troca.getResponseBody()) {
                saida.write(corpo);
            }
        });
    }

    private void servirArquivo(String caminho, String arquivo) throws IOException {
        servirBytes(caminho, Files.readAllBytes(GOLDEN.resolve(arquivo)));
    }

    private void servirBytes(String caminho, byte[] corpo) {
        servidor.createContext(caminho, troca -> {
            troca.getResponseHeaders().add("Content-Type", "application/json");
            troca.sendResponseHeaders(200, corpo.length);
            try (var saida = troca.getResponseBody()) {
                saida.write(corpo);
            }
        });
    }

    private int porta() {
        return servidor.getAddress() == null ? 0 : servidor.getAddress().getPort();
    }
}
