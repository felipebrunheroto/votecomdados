package br.org.votecomdados.ingestion.alesp;

import static org.assertj.core.api.Assertions.assertThat;

import br.org.votecomdados.core.dominio.Enums.Fonte;
import br.org.votecomdados.core.dominio.Enums.TipoJob;
import br.org.votecomdados.ingestion.execucao.ControleDeExecucaoService;
import br.org.votecomdados.ingestion.execucao.Execucao;
import br.org.votecomdados.ingestion.identidade.JobDeCadastroDeParlamentares;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

/**
 * A Alesp contra as amostras reais.
 *
 * <p>São 42 votos em 9 deliberações, escolhidas por conterem <b>todos</b> os
 * casos que o spike de 31/08/2026 encontrou nos 226.067 votos da fonte: os 7
 * códigos de voto em uso, uma deliberação sem data de reunião, duas que
 * misturam F e P, um documento votado que não existe no arquivo de
 * proposituras, uma propositura duplicada byte a byte e uma data sentinela.
 */
@SpringBootTest
@Testcontainers
class JobDaAlespTest {

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

    @Autowired JobDaAlesp job;
    @Autowired LeitorDeXmlAlesp leitor;
    @Autowired LeitorDeDeputadosAlesp leitorDeCadastro;
    @Autowired JobDeCadastroDeParlamentares cadastro;
    @Autowired ControleDeExecucaoService controle;
    @Autowired JdbcClient jdbc;

    Execucao execucao;

    @BeforeEach
    void abrir() {
        execucao = controle.iniciar(Fonte.ALESP, TipoJob.BACKFILL, "{}");
    }

    @AfterEach
    void limpar() {
        controle.close();
        jdbc.sql("DELETE FROM politico").update();
        jdbc.sql("DELETE FROM votacao").update();
        jdbc.sql("DELETE FROM proposicao").update();
        jdbc.sql("DELETE FROM staging.payload_bruto").update();
        jdbc.sql("DELETE FROM staging.registro_rejeitado").update();
        jdbc.sql("DELETE FROM ingestao_execucao").update();
    }

    // ------------------------------------------------------------------------
    // Votos de comissão
    // ------------------------------------------------------------------------

    @Test
    void carrega_as_deliberacoes_com_data_e_sem_ambiguidade() {
        var r = carregarVotos();

        assertThat(r.votacoes()).isEqualTo(6);
        assertThat(jdbc.sql("SELECT count(*) FROM votacao WHERE casa = 'ALESP'")
            .query(Long.class).single()).isEqualTo(6);
    }

    /**
     * Voto de comissão não tem o mesmo peso de deliberação de plenário, e a
     * Alesp só publica comissão em dado estruturado.
     */
    @Test
    void toda_votacao_da_alesp_e_de_comissao() {
        carregarVotos();

        assertThat(jdbc.sql("SELECT DISTINCT ambito::text FROM votacao WHERE casa = 'ALESP'")
            .query(String.class).list()).containsExactly("COMISSAO");
    }

    /**
     * A fonte publica os votos, não o resultado. Contá-los e concluir
     * "aprovada" seria apurar a votação em nome da Casa — em comissão há
     * quórum e regra de desempate que o arquivo não expõe.
     */
    @Test
    void nao_afirma_resultado_que_a_fonte_nao_publica() {
        carregarVotos();

        assertThat(jdbc.sql(
            "SELECT count(*) FROM votacao WHERE casa = 'ALESP' AND aprovada IS NOT NULL")
            .query(Long.class).single()).isZero();
    }

    /**
     * O achado que mudou o desenho da fatia: a chave é o código, não o texto.
     *
     * <p>A amostra tem textos que não existem em {@code mapeamento_voto} — são
     * 477 na fonte inteira. Se o mapeamento fosse por texto, estes votos
     * cairiam em quarentena; pelo código, todos traduzem.
     */
    @Test
    void mapeia_pelo_codigo_e_nao_pelo_texto_livre() {
        naCoorte();
        comVinculoResolvido();
        carregarVotos();

        long semTraducaoSeFosseTexto = jdbc.sql("""
            SELECT count(*) FROM voto_nominal v
             WHERE v.voto_origem NOT IN (SELECT valor_origem FROM mapeamento_voto
                                          WHERE fonte = 'ALESP')
            """).query(Long.class).single();

        assertThat(semTraducaoSeFosseTexto).isPositive();
        assertThat(jdbc.sql("SELECT count(*) FROM voto_nominal WHERE voto IS NULL")
            .query(Long.class).single()).isZero();
    }

    /** Os dois são fato: o código é a chave, o texto é o que a UI mostra. */
    @Test
    void preserva_o_codigo_e_o_texto_da_fonte() {
        naCoorte();
        comVinculoResolvido();
        carregarVotos();

        assertThat(jdbc.sql("""
            SELECT count(*) FROM voto_nominal
             WHERE voto_origem_codigo IS NULL OR voto_origem IS NULL
            """).query(Long.class).single()).isZero();

        assertThat(jdbc.sql("""
            SELECT DISTINCT voto_origem_codigo FROM voto_nominal ORDER BY 1
            """).query(String.class).list())
            .containsExactly("A", "B", "C", "F", "P", "S", "T");
    }

    /**
     * O parlamentar votou, apresentando parecer divergente por escrito.
     * ABSTENCAO diria o oposto do que houve; AUSENTE caluniaria por omissão.
     */
    @Test
    void voto_em_separado_nao_vira_abstencao_nem_ausencia() {
        naCoorte();
        comVinculoResolvido();
        carregarVotos();

        assertThat(jdbc.sql("""
            SELECT DISTINCT voto::text FROM voto_nominal WHERE voto_origem_codigo = 'S'
            """).query(String.class).list()).containsExactly("VOTO_EM_SEPARADO");
    }

    /** A Alesp conta "Em branco" separado de "Abstenção" no placar dela. */
    @Test
    void voto_em_branco_nao_vira_abstencao() {
        naCoorte();
        comVinculoResolvido();
        carregarVotos();

        assertThat(jdbc.sql("""
            SELECT DISTINCT voto::text FROM voto_nominal WHERE voto_origem_codigo = 'B'
            """).query(String.class).list()).containsExactly("BRANCO");
        assertThat(jdbc.sql("""
            SELECT DISTINCT voto::text FROM voto_nominal WHERE voto_origem_codigo = 'A'
            """).query(String.class).list()).containsExactly("ABSTENCAO");
    }

    /**
     * O caso mais delicado da fonte. Numa das deliberações da amostra o texto
     * é "Favorável ao projeto e contrário ao parecer", codificado como P ao
     * lado de linhas F. Gravar os dois como SIM diria que votaram igual quem
     * votou em lados opostos.
     */
    @Test
    void deliberacao_que_mistura_F_e_P_vai_inteira_para_quarentena() {
        naCoorte();
        comVinculoResolvido();
        var r = carregarVotos();

        assertThat(r.deliberacoesAmbiguas()).isEqualTo(2);
        assertThat(jdbc.sql("""
            SELECT count(*) FROM votacao
             WHERE casa = 'ALESP' AND id_externo IN ('8501-773645', '8585-737301')
            """).query(Long.class).single()).isZero();

        assertThat(jdbc.sql("""
            SELECT count(*) FROM staging.registro_rejeitado
             WHERE fonte = 'ALESP' AND motivo = 'VALOR_VOTO_NAO_MAPEADO'
               AND detalhe LIKE '%F (favoravel ao parecer)%'
            """).query(Long.class).single()).isEqualTo(2);
    }

    /**
     * 3 reuniões dos votos não existem no arquivo de reuniões (361 votos na
     * fonte real). Sem data não há como situar a votação no tempo, e uma
     * votação sem data mentiria sobre QUANDO o parlamentar votou.
     */
    @Test
    void deliberacao_sem_data_de_reuniao_nao_vira_votacao() {
        var r = carregarVotos();

        assertThat(r.votosSemData()).isEqualTo(6);
        assertThat(jdbc.sql("""
            SELECT count(*) FROM votacao WHERE casa = 'ALESP' AND id_externo LIKE '6599-%'
            """).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
            SELECT count(*) FROM staging.registro_rejeitado
             WHERE fonte = 'ALESP' AND motivo = 'VOTACAO_DESCONHECIDA'
            """).query(Long.class).single()).isEqualTo(1);
    }

    /**
     * A Alesp publica quem votou, não quem faltou — e não publica a composição
     * da comissão por data. Derivar ausência aqui seria inventar.
     */
    @Test
    void nada_na_alesp_e_derivado() {
        naCoorte();
        comVinculoResolvido();
        carregarVotos();

        assertThat(jdbc.sql("SELECT DISTINCT origem_registro::text FROM voto_nominal")
            .query(String.class).list()).containsExactly("FONTE");
    }

    /**
     * 2.118 dos 18.973 documentos votados não estão no arquivo de proposituras
     * (são pareceres e ofícios). A votação existe e fica sem matéria ligada —
     * descartá-la esconderia um voto que aconteceu.
     */
    @Test
    void documento_sem_propositura_vira_votacao_sem_materia() {
        carregarProposituras();
        carregarVotos();

        assertThat(jdbc.sql("""
            SELECT proposicao_id FROM votacao
             WHERE casa = 'ALESP' AND id_externo = '7320-303553'
            """).query(Long.class).optional()).isEmpty();
    }

    @Test
    void recarregar_nao_duplica() {
        naCoorte();
        comVinculoResolvido();
        carregarProposituras();
        carregarVotos();
        long votacoes = contar("votacao");
        long votos = contar("voto_nominal");
        long proposicoes = contar("proposicao");

        carregarProposituras();
        carregarVotos();

        assertThat(contar("votacao")).isEqualTo(votacoes);
        assertThat(contar("voto_nominal")).isEqualTo(votos);
        assertThat(contar("proposicao")).isEqualTo(proposicoes);
    }

    // ------------------------------------------------------------------------
    // Proposituras e autoria
    // ------------------------------------------------------------------------

    /** 12.413 IdDocumento aparecem duas vezes na fonte, byte a byte iguais. */
    @Test
    void propositura_repetida_na_origem_entra_uma_vez() {
        var r = carregarProposituras();

        assertThat(r.duplicadasNaOrigem()).isEqualTo(1);
        assertThat(r.gravadas()).isEqualTo(8);
        assertThat(contar("proposicao")).isEqualTo(8);
    }

    /**
     * {@code DtPublicacao} vem como {@code 0001-01-01} quando não há data.
     * Gravá-la como data real poria uma matéria de 2022 no ano 1.
     */
    @Test
    void data_sentinela_nao_vira_data_real() {
        carregarProposituras();

        assertThat(jdbc.sql("""
            SELECT data_apresentacao FROM proposicao
             WHERE casa = 'ALESP' AND id_externo = '1000429543'
            """).query(LocalDate.class).optional()).isEmpty();

        assertThat(jdbc.sql("""
            SELECT count(*) FROM proposicao
             WHERE casa = 'ALESP' AND data_apresentacao < DATE '1900-01-01'
            """).query(Long.class).single()).isZero();
    }

    /** IdNatureza vira sigla legível; sem isso sigla_tipo seria um número. */
    @Test
    void natureza_vira_sigla_legivel() {
        carregarProposituras();

        assertThat(jdbc.sql("SELECT DISTINCT sigla_tipo FROM proposicao ORDER BY 1")
            .query(String.class).list()).contains("PL");
    }

    /**
     * "Governador" assina 19.887 matérias e não é candidato. Entra como nome
     * na autoria, sem perfil — um nome numa lista não é um dossiê.
     */
    @Test
    void autor_fora_da_coorte_entra_como_nome_sem_perfil() {
        carregarProposituras();
        int vinculos = job.carregarAutoria(execucao, autorias());

        assertThat(vinculos).isPositive();
        assertThat(jdbc.sql("""
            SELECT count(*) FROM proposicao_autor
             WHERE autor_nome IS NOT NULL AND politico_id IS NULL
            """).query(Long.class).single()).isEqualTo(vinculos);
    }

    // ------------------------------------------------------------------------
    // Identidade: a armadilha do IdDeputado
    // ------------------------------------------------------------------------

    /**
     * {@code IdDeputado} tem significados DIFERENTES em arquivos diferentes da
     * mesma fonte: no cadastro é o id do portal, nos votos e na autoria é o id
     * do SPL, que o cadastro publica como {@code IdSPL}.
     *
     * <p>Na amostra, Carlão Pignatari tem {@code IdDeputado} 431 — e 431 é o
     * id SPL de <b>Enio Tatto</b>. Casar pelo campo de nome óbvio daria a um
     * os votos do outro.
     */
    @Test
    void vinculo_usa_IdSPL_e_nao_IdDeputado() {
        naCoorte();
        cadastro.carregar(execucao, cadastroDeDeputados().iterator(), leitorDeCadastro::ler);

        assertThat(jdbc.sql("""
            SELECT identificador FROM identificador_externo
             WHERE sistema = 'ALESP' AND identificador = '431'
            """).query(String.class).optional()).isPresent();

        // 372 é o IdDeputado de Enio Tatto no cadastro; se ele aparecesse
        // aqui, o vínculo teria sido feito pelo campo errado.
        assertThat(jdbc.sql("""
            SELECT count(*) FROM identificador_externo
             WHERE sistema = 'ALESP' AND identificador IN ('372', '299', '347')
            """).query(Long.class).single()).isZero();
    }

    /**
     * Sem ano de nascimento (a Alesp publica só dia/mês), nenhum vínculo da
     * Alesp pode ser determinístico: todos vão para a fila do curador.
     */
    @Test
    void vinculo_da_alesp_nunca_e_deterministico() {
        naCoorte();
        cadastro.carregar(execucao, cadastroDeDeputados().iterator(), leitorDeCadastro::ler);

        assertThat(jdbc.sql("""
            SELECT DISTINCT metodo_resolucao::text FROM identificador_externo
             WHERE sistema = 'ALESP'
            """).query(String.class).list()).containsExactly("FUZZY");
        assertThat(jdbc.sql("""
            SELECT count(*) FROM identificador_externo
             WHERE sistema = 'ALESP' AND revisado_manualmente
            """).query(Long.class).single()).isZero();
    }

    /**
     * O cadastro da Alesp traz telefone, e-mail e <b>placa de veículo</b>.
     * Nenhum tem uso na resolução de identidade, e nenhum pode chegar ao
     * staging — é o argumento da allowlist em forma de teste.
     */
    @Test
    void dado_pessoal_do_cadastro_nao_chega_ao_staging() {
        naCoorte();
        cadastro.carregar(execucao, cadastroDeDeputados().iterator(), leitorDeCadastro::ler);

        var payloads = jdbc.sql("""
            SELECT payload::text FROM staging.payload_bruto
             WHERE fonte = 'ALESP' AND recurso = 'parlamentar'
            """).query(String.class).list();

        assertThat(payloads).isNotEmpty();
        assertThat(payloads).noneMatch(p -> p.contains("Telefone")
                                         || p.contains("PlacaVeiculo")
                                         || p.contains("Email")
                                         || p.contains("Biografia"));
        assertThat(payloads).allMatch(p -> p.contains("IdSPL"));
    }

    // ------------------------------------------------------------------------
    // Apoio
    // ------------------------------------------------------------------------

    private JobDaAlesp.ResultadoVotacoes carregarVotos() {
        Map<String, LocalDate> datas;
        try (var fluxo = leitor.ler(GOLDEN.resolve("alesp-reunioes-comissao-amostra.xml"),
                                    "ReuniaoComissao")) {
            datas = job.lerReunioes(execucao, fluxo);
        }
        try (var fluxo = leitor.ler(GOLDEN.resolve("alesp-votacoes-comissao-amostra.xml"),
                                    "ReuniaoComissaoVotacao")) {
            return job.carregarVotos(execucao, fluxo, datas);
        }
    }

    private JobDaAlesp.Resultado carregarProposituras() {
        Map<String, String> naturezas;
        try (var fluxo = leitor.ler(GOLDEN.resolve("alesp-naturezas-amostra.xml"),
                                    "natureza")) {
            naturezas = job.lerNaturezas(fluxo);
        }
        try (var fluxo = leitor.ler(GOLDEN.resolve("alesp-proposituras-amostra.xml"),
                                    "propositura")) {
            return job.carregarProposituras(execucao, fluxo, naturezas);
        }
    }

    private List<JsonNode> autorias() {
        try (var fluxo = leitor.ler(GOLDEN.resolve("alesp-documento-autor-amostra.xml"),
                                    "DocumentoAutor")) {
            var lista = new ArrayList<JsonNode>();
            fluxo.forEach(lista::add);
            return lista;
        }
    }

    private List<JsonNode> cadastroDeDeputados() {
        try (var fluxo = leitor.ler(GOLDEN.resolve("alesp-deputados-amostra.xml"),
                                    "Deputado")) {
            var lista = new ArrayList<JsonNode>();
            fluxo.forEach(lista::add);
            return lista;
        }
    }

    /**
     * Põe na coorte quem vota nas deliberações válidas da amostra. NÃO cria
     * o vínculo: quem precisa dele chama {@link #comVinculoResolvido()}.
     */
    private void naCoorte() {
        record Deputado(String idSpl, String nome) {}
        List<Deputado> deputados = List.of(
            new Deputado("10585", "Alex Manente"), new Deputado("10595", "Davi Zaia"),
            new Deputado("10604", "Haifa Madi"), new Deputado("10607", "José Cândido"),
            new Deputado("10614", "Olímpio Gomes"), new Deputado("10624", "Samuel Moreira"),
            new Deputado("11185", "Pedro Bigardi"), new Deputado("12407", "Geraldo Cruz"),
            new Deputado("12425", "Doutor Ulysses"), new Deputado("17", "Maria Lúcia Prandi"),
            new Deputado("197", "Jorge Caruso"), new Deputado("225", "Pedro Tobias"),
            new Deputado("430", "Simão Pedro"), new Deputado("431", "Enio Tatto"),
            new Deputado("433", "Vicente Cândido"), new Deputado("434", "Analice Fernandes"),
            new Deputado("436", "Maria Lúcia Amary"), new Deputado("441", "Roberto Alves"),
            new Deputado("447", "Orlando Morando"), new Deputado("449", "Waldir Agnello"),
            new Deputado("458", "Said Mourad"), new Deputado("469", "Roberto Felício"),
            new Deputado("68", "Edmir Chedid"), new Deputado("82", "Conte Lopes"));

        for (Deputado d : deputados) {
            var id = jdbc.sql("""
                INSERT INTO politico (nome_civil, nome_urna) VALUES (:nome, :nome)
                RETURNING id
                """).param("nome", d.nome()).query(java.util.UUID.class).single();
            jdbc.sql("""
                INSERT INTO candidatura (politico_id, sq_candidato_tse, ano_eleicao,
                                         turno, cargo, esfera, uf, partido_sigla, status)
                VALUES (:id, :sq, 2026, 1, 'DEPUTADO_ESTADUAL', 'ESTADUAL', 'SP', 'XX',
                        'NAO_INFORMADO')
                """).param("id", id).param("sq", "SQ-" + d.idSpl()).update();
        }
    }

    /**
     * Vínculo já resolvido, como estaria depois do job de cadastro. Separado de
     * {@link #naCoorte()} de propósito: os testes de identidade precisam da
     * coorte SEM vínculo, senão estariam medindo o próprio setup.
     */
    private void comVinculoResolvido() {
        // O identificador é o id SPL, que sq_candidato_tse carrega prefixado —
        // é o mesmo id que aparece em <IdDeputado> nos votos.
        jdbc.sql("""
            INSERT INTO identificador_externo (politico_id, sistema, identificador)
            SELECT p.id, 'ALESP', replace(c.sq_candidato_tse, 'SQ-', '')
              FROM politico p JOIN candidatura c ON c.politico_id = p.id
            """).update();
    }

    private long contar(String tabela) {
        return jdbc.sql("SELECT count(*) FROM " + tabela).query(Long.class).single();
    }
}
