package br.org.votecomdados.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Testes de integração contra um Postgres real, com o schema aplicado por
 * Flyway e o seed de desenvolvimento carregado.
 *
 * Estes testes carregam mais peso do que o habitual: como o acesso a dados usa
 * SQL escrito à mão (ver docs/BACKEND.md, nota sobre jOOQ), não há verificação
 * em tempo de compilação de que as consultas batem com o schema. É aqui que
 * um nome de coluna errado aparece.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Testcontainers
class ApiIntegracaoTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("votecomdados");

    @DynamicPropertySource
    static void configurar(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registro.add("spring.datasource.username", POSTGRES::getUsername);
        registro.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort int porta;
    @Autowired JdbcClient jdbc;
    RestClient http;

    @BeforeEach
    void prepararCliente() {
        http = RestClient.create("http://localhost:" + porta);
    }

    /** Corpo de uma resposta bem-sucedida. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> obter(String caminho) {
        return http.get().uri(caminho).retrieve().body(Map.class);
    }

    /**
     * Resposta completa sem lançar em 4xx/5xx — o RestClient estoura por padrão,
     * e aqui o código de status é justamente o que está sob teste.
     */
    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> obterEntidade(String caminho) {
        return http.get().uri(caminho)
            .retrieve()
            .onStatus(HttpStatusCode::isError, (req, res) -> { })
            .toEntity(Map.class);
    }

    private static final String COM_ATUACAO = "a1000000-0000-4000-8000-000000000001";
    private static final String SEM_ATUACAO = "a1000000-0000-4000-8000-000000000002";
    private static final String PRE_2001    = "a1000000-0000-4000-8000-000000000003";

    @Test
    void busca_retorna_candidatos_com_paginacao() {
        var r = obter("/api/v1/politicos?pageSize=5");
        assertThat(r).containsKeys("data", "pagination");
        assertThat((List<?>) r.get("data")).isNotEmpty();
    }

    @Test
    void busca_por_nome_ignora_acento_e_caixa() {
        var r = obter("/api/v1/politicos?q=acacio");
        var dados = (List<Map<String, Object>>) r.get("data");
        assertThat(dados).isNotEmpty();
        assertThat(dados.get(0).get("nomeCivil").toString()).contains("Acácio");
    }

    @Test
    void filtro_com_atuacao_exclui_quem_nao_tem_mandato() {
        var r = obter("/api/v1/politicos?comAtuacao=true&pageSize=100");
        var dados = (List<Map<String, Object>>) r.get("data");
        assertThat(dados).isNotEmpty();
        assertThat(dados).allSatisfy(p ->
            assertThat(p.get("possuiAtuacaoLegislativa")).isEqualTo(true));
    }

    @Test
    void perfil_traz_trajetoria_dos_tres_niveis() {
        var r = obter("/api/v1/politicos/" + COM_ATUACAO);
        var trajetoria = (List<Map<String, Object>>) r.get("trajetoria");

        assertThat(trajetoria).hasSize(4);
        // Da disputa mais recente para a mais antiga.
        assertThat(trajetoria.get(0).get("anoEleicao")).isEqualTo(2026);
        assertThat(trajetoria).extracting(c -> c.get("esfera"))
            .contains("FEDERAL", "ESTADUAL", "MUNICIPAL");
    }

    @Test
    void perfil_de_sao_paulo_declara_que_alesp_nao_publica_voto_de_plenario() {
        var r = obter("/api/v1/politicos/" + COM_ATUACAO);
        var cobertura = (List<Map<String, Object>>) r.get("cobertura");

        // Precedência por UF: SP resolve para a Alesp, não para o fallback
        // genérico "ainda não cobrimos".
        assertThat(cobertura).anySatisfy(c -> {
            assertThat(c.get("esfera")).isEqualTo("ESTADUAL");
            assertThat(c.get("uf")).isEqualTo("SP");
            assertThat(c.get("recurso")).isEqualTo("voto_nominal");
            assertThat(c.get("status")).isEqualTo("NAO_PUBLICADO_PELA_FONTE");
        });
    }

    @Test
    void perfil_inexistente_devolve_404_no_envelope_padrao() {
        var r = obterEntidade("/api/v1/politicos/00000000-0000-4000-8000-000000000000");
        assertThat(r.getStatusCode().value()).isEqualTo(404);
        var erro = (Map<String, Object>) r.getBody().get("error");
        assertThat(erro.get("code")).isEqualTo("NOT_FOUND");
    }

    @Test
    void votacoes_preservam_o_rotulo_original_da_fonte() {
        var r = obter("/api/v1/politicos/" + COM_ATUACAO + "/votacoes");
        var dados = (List<Map<String, Object>>) r.get("data");

        // O voto da Alesp é texto livre e não pode ser reduzido ao enum.
        assertThat(dados).anySatisfy(v -> {
            assertThat(v.get("votoOrigem")).isEqualTo("Favorável ao parecer");
            assertThat(v.get("voto")).isEqualTo("SIM");
            assertThat(v.get("ambito")).isEqualTo("COMISSAO");
            assertThat(v.get("notaMetodologica").toString()).contains("comissão");
        });
    }

    @Test
    void obstrucao_vem_com_nota_de_metodologia() {
        var r = obter("/api/v1/politicos/" + COM_ATUACAO + "/votacoes");
        var dados = (List<Map<String, Object>>) r.get("data");

        assertThat(dados).anySatisfy(v -> {
            assertThat(v.get("voto")).isEqualTo("OBSTRUCAO");
            assertThat(v.get("notaMetodologica").toString()).contains("mérito");
        });
    }

    @Test
    void mandato_anterior_a_2001_nao_tem_votacoes() {
        var r = obter("/api/v1/politicos/" + PRE_2001 + "/votacoes");
        assertThat((List<?>) r.get("data")).isEmpty();
    }

    @Test
    void candidato_sem_mandato_tem_perfil_mas_listas_vazias() {
        var perfil = obter("/api/v1/politicos/" + SEM_ATUACAO);
        assertThat(perfil.get("possuiAtuacaoLegislativa")).isEqualTo(false);

        var props = obter("/api/v1/politicos/" + SEM_ATUACAO + "/proposicoes");
        assertThat((List<?>) props.get("data")).isEmpty();
    }

    @Test
    void proposicao_lista_autoria_completa_com_coautor_sem_perfil() {
        var r = obter("/api/v1/proposicoes/1197773");
        var autores = (List<Map<String, Object>>) r.get("autores");

        assertThat(autores).hasSize(4);
        // Coautor fora da coorte: aparece pelo nome, sem id — a UI não cria link.
        assertThat(autores).anySatisfy(a -> {
            assertThat(a.get("nome")).isEqualTo("Reginaldo Tavares de Almeida");
            assertThat(a.get("politicoId")).isNull();
        });
        assertThat(autores).anySatisfy(a ->
            assertThat(a.get("politicoId")).isNotNull());
    }

    @Test
    void votacao_nominal_traz_placar_agregado() {
        var r = obter("/api/v1/votacoes/555111");
        var placar = (Map<String, Object>) r.get("placar");

        assertThat(placar).isNotNull();
        assertThat((Integer) placar.get("sim")).isGreaterThan(0);
        assertThat(r.get("ambito")).isEqualTo("PLENARIO");
    }

    @Test
    void votacao_simbolica_tem_placar_nulo_e_nao_zero() {
        var r = obter("/api/v1/votacoes/555112");

        // Zero sugeriria que ninguém votou; o correto é que a Casa não registra
        // o voto individual.
        assertThat(r.get("placar")).isNull();
        assertThat(r.get("tipo")).isEqualTo("SIMBOLICA");
        assertThat(r.get("observacao").toString())
            .contains("não o voto de cada parlamentar");
    }

    /**
     * Achado B1 (01/09/2026): esta rota nunca existiu, e `generateStaticParams`
     * do frontend já chamava por ela — respondia 500 disfarçado de "erro
     * interno" pelo handler genérico. Sem paginação de propósito: existe só
     * para alimentar o build estático, não para navegação.
     */
    @Test
    void lista_todos_os_ids_de_votacoes_sem_paginar() {
        var r = obter("/api/v1/votacoes");
        var ids = (List<Number>) r.get("ids");

        assertThat(ids).isNotEmpty();
        assertThat(ids.stream().map(Number::longValue))
            .containsExactlyInAnyOrderElementsOf(
                jdbc.sql("SELECT id FROM votacao").query(Long.class).list());
    }

    @Test
    void lista_todos_os_ids_de_proposicoes_sem_paginar() {
        var r = obter("/api/v1/proposicoes");
        var ids = (List<Number>) r.get("ids");

        assertThat(ids).isNotEmpty();
        assertThat(ids.stream().map(Number::longValue))
            .containsExactlyInAnyOrderElementsOf(
                jdbc.sql("SELECT id FROM proposicao").query(Long.class).list());
    }

    @Test
    void status_das_fontes_reporta_falha_sem_perder_a_ultima_data_boa() {
        var r = obter("/api/v1/meta/status");
        var fontes = (List<Map<String, Object>>) r.get("fontes");

        assertThat(fontes).anySatisfy(f -> {
            assertThat(f.get("fonte")).isEqualTo("ALESP");
            assertThat(f.get("status")).isEqualTo("FALHA");
            // A data continua sendo a da última execução bem-sucedida.
            assertThat(f.get("ultimaAtualizacao").toString()).startsWith("2026-08-15");
        });
    }

    @Test
    void pageSize_acima_do_limite_e_rejeitado() {
        var r = obterEntidade("/api/v1/politicos?pageSize=500");
        assertThat(r.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void cargo_fora_do_enum_e_400_e_nao_500() {
        // Encontrado ao rodar a coleção Postman: sem tratar
        // MethodArgumentTypeMismatchException, um valor inválido de enum virava
        // 500 — a plataforma reportando como falha dela um erro de quem chamou.
        var r = obterEntidade("/api/v1/politicos?cargo=IMPERADOR");
        assertThat(r.getStatusCode().value()).isEqualTo(400);

        var erro = (Map<String, Object>) r.getBody().get("error");
        assertThat(erro.get("code")).isEqualTo("BAD_REQUEST");
        // A mensagem lista os valores aceitos, para o cliente se corrigir.
        assertThat(erro.get("message").toString()).contains("DEPUTADO_FEDERAL");
    }

    /**
     * O perfil é servido pela projeção (R4), não pelas três consultas
     * normalizadas — mas o caminho antigo continua vivo como rede de proteção.
     *
     * O teste exercita os dois e exige a MESMA resposta: se divergirem, a
     * projeção está mentindo, que é o único risco real do CQRS aqui.
     */
    @Test
    void perfil_da_projecao_e_do_caminho_normalizado_coincidem() {
        var id = COM_ATUACAO;

        assertThat(jdbc.sql("SELECT count(*) FROM perfil_leitura").query(Long.class).single())
            .as("o seed deve reconstruir a projecao, como o worker faz em producao")
            .isEqualTo(jdbc.sql("SELECT count(*) FROM politico").query(Long.class).single());

        var pelaProjecao = obter("/api/v1/politicos/" + id);

        // Sem a linha na projeção, o repositório cai no caminho normalizado.
        jdbc.sql("DELETE FROM perfil_leitura WHERE politico_id = :id::uuid")
            .param("id", id).update();
        var peloFallback = obter("/api/v1/politicos/" + id);

        jdbc.sql("SELECT reconstruir_perfil_leitura()").query(Long.class).single();

        assertThat(peloFallback).isEqualTo(pelaProjecao);
    }

    @Test
    void readiness_nao_depende_de_cache() {
        var r = obterEntidade("/actuator/health/readiness");
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody().get("status")).isEqualTo("UP");
    }
}
