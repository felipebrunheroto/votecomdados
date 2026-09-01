package br.org.votecomdados.ingestion.identidade;

import static org.assertj.core.api.Assertions.assertThat;

import br.org.votecomdados.core.dominio.Enums.Fonte;
import br.org.votecomdados.core.dominio.Enums.TipoJob;
import br.org.votecomdados.ingestion.execucao.ControleDeExecucaoService;
import br.org.votecomdados.ingestion.execucao.Execucao;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * O maior risco do produto, testado contra o dado real da Câmara.
 *
 * <p>A amostra em {@code db/golden/camara-deputados-amostra.csv} é um recorte
 * verbatim de {@code deputados.csv}, escolhido para conter os casos que
 * importam: nome com acento, deputado sem data de nascimento e a coluna
 * {@code cpf} vazia — que é o motivo de a resolução não poder usar CPF.
 */
@SpringBootTest
@Testcontainers
class ResolucaoDeIdentidadeTest {

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

    private static final Path GOLDEN =
        Path.of("..", "..", "db", "golden", "camara-deputados-amostra.csv");

    @Autowired JobDeCadastroDeParlamentares job;
    @Autowired ServicoDeResolucaoDeIdentidade resolucao;
    @Autowired LeitorDeDeputadosCamara leitor;
    @Autowired ControleDeExecucaoService controle;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    Execucao execucao;

    @BeforeEach
    void abrir() {
        execucao = controle.iniciar(Fonte.CAMARA, TipoJob.BACKFILL, "{}");
    }

    @AfterEach
    void limpar() {
        controle.close();
        jdbc.sql("DELETE FROM politico").update();
        jdbc.sql("DELETE FROM staging.payload_bruto").update();
        jdbc.sql("DELETE FROM staging.registro_rejeitado").update();
        jdbc.sql("DELETE FROM ingestao_execucao").update();
    }

    /** O caso geral: nome civil + nascimento batem, e o vínculo é confiável. */
    @Test
    void casa_por_nome_civil_e_data_de_nascimento() throws IOException {
        var deputado = primeiroDoGolden();
        var p = leitor.ler(deputado);
        var politicoId = candidatoDaCoorte(p.nomeCivil(), p.dataNascimento(), "SP");

        var vinculo = resolucao.resolver(p);

        assertThat(vinculo.desfecho()).isEqualTo(Vinculo.Desfecho.RESOLVIDO);
        assertThat(vinculo.politicoId()).isEqualTo(politicoId);
        assertThat(vinculo.metodo()).isEqualTo(
            br.org.votecomdados.core.dominio.Enums.MetodoResolucao.DETERMINISTICO);
    }

    /** Acento não pode separar a mesma pessoa em duas. */
    @Test
    void acentuacao_e_caixa_nao_impedem_o_casamento() throws IOException {
        var comAcento = golden().stream()
            .filter(l -> l.get("nomeCivil").asString().contains("Á")
                      || l.get("nomeCivil").asString().contains("É"))
            .findFirst().orElseThrow();
        var p = leitor.ler(comAcento);
        String semAcento = java.text.Normalizer
            .normalize(p.nomeCivil(), java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "").toLowerCase();
        candidatoDaCoorte(semAcento, p.dataNascimento(), "AP");

        assertThat(resolucao.resolver(p).desfecho()).isEqualTo(Vinculo.Desfecho.RESOLVIDO);
    }

    /**
     * O erro clássico do domínio: dois homônimos fundidos num perfil só. Aqui a
     * regra é não escolher.
     */
    @Test
    void homonimos_com_mesmo_nascimento_viram_ambiguidade_nao_aposta() throws IOException {
        var p = leitor.ler(primeiroDoGolden());
        candidatoDaCoorte(p.nomeCivil(), p.dataNascimento(), "SP");
        candidatoDaCoorte(p.nomeCivil(), p.dataNascimento(), "BA");

        var vinculo = resolucao.resolver(p);

        assertThat(vinculo.desfecho()).isEqualTo(Vinculo.Desfecho.AMBIGUO);
        assertThat(vinculo.politicoId()).as("nao se escolhe um dos dois").isNull();
    }

    @Test
    void parlamentar_sem_candidatura_em_2026_fica_fora_da_coorte() throws IOException {
        var p = leitor.ler(primeiroDoGolden());

        assertThat(resolucao.resolver(p).desfecho()).isEqualTo(Vinculo.Desfecho.FORA_DA_COORTE);
    }

    /**
     * Sem data de nascimento resta o nome — e nome sozinho nunca vira vínculo
     * confirmado.
     */
    @Test
    void sem_data_de_nascimento_o_vinculo_fica_pendente_de_curadoria() throws IOException {
        var semData = golden().stream()
            .filter(l -> l.get("dataNascimento").asString().isBlank())
            .findFirst().orElseThrow();
        var p = leitor.ler(semData);
        assertThat(p.dataNascimento()).as("a amostra precisa ter esse caso").isNull();

        candidatoDaCoorte(p.nomeCivil(), null, p.uf());

        var vinculo = resolucao.resolver(p);

        assertThat(vinculo.desfecho()).isEqualTo(Vinculo.Desfecho.PENDENTE_DE_CURADORIA);
        assertThat(vinculo.score()).isNotNull();
    }

    @Test
    void o_cadastro_inteiro_classifica_cada_parlamentar() throws IOException {
        var comCoorte = leitor.ler(primeiroDoGolden());
        candidatoDaCoorte(comCoorte.nomeCivil(), comCoorte.dataNascimento(), "MT");

        var r = job.carregar(execucao, golden().iterator());

        assertThat(r.resolvidos()).isEqualTo(1);
        assertThat(r.foraDaCoorte()).isGreaterThan(0);
        assertThat(r.resolvidos() + r.pendentesDeCuradoria() + r.ambiguos()
                   + r.foraDaCoorte()).isEqualTo(golden().size());
    }

    /** A distinção que faz a métrica de quarentena valer alguma coisa. */
    @Test
    void fora_da_coorte_nao_polui_a_fila_que_exige_acao() throws IOException {
        job.carregar(execucao, golden().iterator());

        assertThat(jdbc.sql("""
                SELECT count(*) FROM staging.registro_rejeitado
                 WHERE motivo = 'FORA_DA_COORTE'
                """).query(Long.class).single()).isGreaterThan(0);

        assertThat(jdbc.sql("""
                SELECT count(*) FROM staging.registro_rejeitado
                 WHERE resolvido_em IS NULL AND motivo <> 'FORA_DA_COORTE'
                """).query(Long.class).single())
            .as("nada aqui exige acao humana neste cenario").isZero();
    }

    @Test
    void vinculo_fuzzy_entra_como_nao_revisado_e_aparece_na_fila_do_curador()
            throws IOException {
        var semData = golden().stream()
            .filter(l -> l.get("dataNascimento").asString().isBlank())
            .findFirst().orElseThrow();
        var p = leitor.ler(semData);
        candidatoDaCoorte(p.nomeCivil(), null, p.uf());

        resolucao.gravar(p, resolucao.resolver(p));

        assertThat(resolucao.pendentesDeCuradoria()).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT revisado_manualmente FROM identificador_externo
                """).query(Boolean.class).single())
            .as("fuzzy nao conta como confirmado ate alguem olhar").isFalse();
    }

    @Test
    void reexecutar_nao_reclassifica_quem_ja_tem_vinculo() throws IOException {
        var p = leitor.ler(primeiroDoGolden());
        candidatoDaCoorte(p.nomeCivil(), p.dataNascimento(), "MT");

        job.carregar(execucao, golden().iterator());
        var r = job.carregar(execucao, golden().iterator());

        assertThat(r.resolvidos()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM identificador_externo")
            .query(Long.class).single()).isEqualTo(1);
    }

    /** O motivo de o CPF não entrar na resolução, provado no dado real. */
    @Test
    void a_coluna_cpf_da_camara_vem_vazia_e_por_isso_nao_serve() throws IOException {
        var comCpf = golden().stream()
            .filter(l -> !l.get("cpf").asString().isBlank()).count();

        assertThat(comCpf)
            .as("se um dia vier preenchida, a resolucao pode melhorar — e este "
                + "teste avisa")
            .isZero();
    }

    // ---------------------------------------------------------------- auxiliares

    private UUID candidatoDaCoorte(String nomeCivil, LocalDate nascimento, String uf) {
        var id = jdbc.sql("""
                INSERT INTO politico (nome_civil, data_nascimento) VALUES (:nome, :nasc)
                RETURNING id
                """)
            .param("nome", nomeCivil).param("nasc", nascimento)
            .query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO candidatura (politico_id, sq_candidato_tse, ano_eleicao, cargo,
                                         esfera, uf, partido_sigla, status)
                VALUES (:id, 'sq-' || gen_random_uuid(), 2026, 'DEPUTADO_FEDERAL',
                        'FEDERAL', :uf, 'XYZ', 'DEFERIDO')
                """).param("id", id).param("uf", uf == null ? "SP" : uf).update();
        return id;
    }

    private JsonNode primeiroDoGolden() throws IOException {
        return golden().getFirst();
    }

    /** Lê o CSV verbatim da fonte, sem reescrevê-lo em fixture sintética. */
    private List<JsonNode> golden() throws IOException {
        var linhas = Files.readAllLines(GOLDEN, StandardCharsets.UTF_8);
        var cabecalho = separar(linhas.getFirst().replace("﻿", ""));
        var registros = new ArrayList<JsonNode>();
        for (String linha : linhas.subList(1, linhas.size())) {
            var valores = separar(linha);
            ObjectNode no = json.createObjectNode();
            for (int i = 0; i < cabecalho.size(); i++) {
                no.put(cabecalho.get(i), i < valores.size() ? valores.get(i) : "");
            }
            registros.add(no);
        }
        return registros;
    }

    /** CSV do arquivo real: separador `;` e todo campo entre aspas. */
    private static List<String> separar(String linha) {
        var campos = new ArrayList<String>();
        var atual = new StringBuilder();
        boolean dentroDeAspas = false;
        for (char c : linha.toCharArray()) {
            if (c == '"') dentroDeAspas = !dentroDeAspas;
            else if (c == ';' && !dentroDeAspas) {
                campos.add(atual.toString());
                atual.setLength(0);
            } else atual.append(c);
        }
        campos.add(atual.toString());
        return campos;
    }
}
