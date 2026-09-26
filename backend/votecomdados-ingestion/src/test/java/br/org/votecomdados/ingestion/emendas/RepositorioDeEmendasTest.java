package br.org.votecomdados.ingestion.emendas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.org.votecomdados.core.dominio.Enums.LocalidadeEmenda;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
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
 * O SQL do repositório, contra Postgres de verdade.
 *
 * <p>Existe porque neste projeto erro de SQL não aparece em compilação: o
 * acesso a dados é SQL escrito à mão, e já houve caso de parâmetro sem tipo
 * derrubando a ingestão só em produção. Aqui o CHECK, o upsert e o
 * {@code coalesce} são exercidos de fato.
 */
@SpringBootTest
@Testcontainers
class RepositorioDeEmendasTest {

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

    @Autowired RepositorioDeEmendas repositorio;
    @Autowired JdbcClient jdbc;

    UUID comMandatoFederal;
    UUID homonimoSemMandato;

    @BeforeEach
    void semear() {
        // Os políticos também precisam sair entre testes. Sem isto, o teste que
        // cria um terceiro homônimo COM mandato federal envenena os demais —
        // eles passam a achar dois e devolver vazio —, e o resultado passa a
        // depender da ordem de execução, que o JUnit não garante.
        jdbc.sql("DELETE FROM emenda").update();
        jdbc.sql("DELETE FROM identificador_externo ie USING politico p"
            + " WHERE ie.politico_id = p.id AND p.nome_civil LIKE 'HELENA%'").update();
        jdbc.sql("DELETE FROM politico WHERE nome_civil LIKE 'HELENA%'").update();

        comMandatoFederal = criarPolitico("HELENA MARQUES VILAR", "HELENA MARQUES");
        jdbc.sql("""
                INSERT INTO identificador_externo (politico_id, sistema, identificador)
                VALUES (:p, 'CAMARA', :id)
                ON CONFLICT DO NOTHING
                """)
            .param("p", comMandatoFederal).param("id", "t-" + comMandatoFederal).update();

        // Mesmo nome, sem mandato federal: é o candidato a vereador homônimo
        // que a âncora existe para NÃO casar.
        homonimoSemMandato = criarPolitico("HELENA MARQUES VILAR", "HELENA DO BAIRRO");
    }

    private UUID criarPolitico(String civil, String urna) {
        return jdbc.sql("""
                INSERT INTO politico (nome_civil, nome_urna) VALUES (:c, :u) RETURNING id
                """)
            .param("c", civil).param("u", urna).query(UUID.class).single();
    }

    private Emenda emenda(String codigo, LocalidadeEmenda tipo, String municipio, String uf,
                          String pago, String restoPago) {
        return new Emenda(codigo, 2025, codigo.substring(8), "Emenda Individual",
            codigo.substring(4, 8), "HELENA MARQUES VILAR", null, null,
            municipio != null ? municipio + " - " + uf : "MÚLTIPLO",
            tipo, municipio, uf, "Saude", "Atencao basica",
            new BigDecimal("1000.00"), new BigDecimal("500.00"),
            pago == null ? null : new BigDecimal(pago),
            BigDecimal.ZERO, BigDecimal.ZERO,
            restoPago == null ? null : new BigDecimal(restoPago));
    }

    // ---- vínculo ---------------------------------------------------------

    /**
     * A medição que justifica a âncora: contra a base inteira, 20 autores de
     * 2025 ficavam ambíguos; restrito a quem tem mandato federal, sobra 1.
     */
    @Test
    void casa_so_com_quem_tem_mandato_federal() {
        Optional<UUID> achado = repositorio.casarPorNome("HELENA MARQUES VILAR");

        assertThat(achado).contains(comMandatoFederal);
        assertThat(achado).get().isNotEqualTo(homonimoSemMandato);
    }

    @Test
    void casa_tambem_pelo_nome_de_urna() {
        assertThat(repositorio.casarPorNome("HELENA MARQUES")).contains(comMandatoFederal);
    }

    /** Empate não se desempata por regra: atribuir à pessoa errada é pior. */
    @Test
    void dois_com_mandato_federal_e_mesmo_nome_ficam_sem_vinculo() {
        UUID outro = criarPolitico("HELENA MARQUES VILAR", "OUTRA HELENA");
        jdbc.sql("""
                INSERT INTO identificador_externo (politico_id, sistema, identificador)
                VALUES (:p, 'SENADO', :id)
                """)
            .param("p", outro).param("id", "t2-" + outro).update();

        assertThat(repositorio.casarPorNome("HELENA MARQUES VILAR")).isEmpty();
    }

    @Test
    void vinculo_gravado_e_reencontrado_pelo_codigo_do_autor() {
        assertThat(repositorio.vinculoConhecido("4184")).isEmpty();
        repositorio.gravarVinculo("4184", comMandatoFederal);
        assertThat(repositorio.vinculoConhecido("4184")).contains(comMandatoFederal);
    }

    // ---- gravação --------------------------------------------------------

    @Test
    void grava_emenda_de_municipio_com_cidade_e_uf() {
        repositorio.salvar(emenda("202541840001", LocalidadeEmenda.MUNICIPIO,
            "CARUARU", "PE", "820000.00", "0.00"));

        var lido = jdbc.sql("SELECT municipio_nome, uf, valor_pago FROM emenda WHERE codigo = ?")
            .param("202541840001").query().singleRow();
        assertThat(lido.get("municipio_nome")).isEqualTo("CARUARU");
        assertThat(lido.get("uf")).isEqualTo("PE");
    }

    /**
     * O CHECK do schema impede o estado incoerente — localidade que não é de
     * município carregando cidade, ou de município sem ela.
     */
    @Test
    void multiplo_com_cidade_preenchida_e_recusado_pelo_banco() {
        Emenda incoerente = new Emenda("202541840002", 2025, "0002", "Emenda Individual",
            "4184", "X", null, null, "MÚLTIPLO", LocalidadeEmenda.MULTIPLO,
            "CARUARU", "PE", null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> repositorio.salvar(incoerente))
            .hasMessageContaining("municipio_so_quando_e_municipio");
    }

    /**
     * Santos, 2025: pago R$ 0,00 e R$ 600 mil por restos a pagar. Guardar só
     * "pago" faria a página dizer que a cidade não recebeu nada.
     */
    @Test
    void guarda_restos_a_pagar_separado_do_pago() {
        repositorio.salvar(emenda("202541840003", LocalidadeEmenda.MUNICIPIO,
            "SANTOS", "SP", "0.00", "599999.98"));

        var lido = jdbc.sql(
                "SELECT valor_pago, valor_resto_pago FROM emenda WHERE codigo = ?")
            .param("202541840003").query().singleRow();
        assertThat((BigDecimal) lido.get("valor_pago")).isEqualByComparingTo("0.00");
        assertThat((BigDecimal) lido.get("valor_resto_pago")).isEqualByComparingTo("599999.98");
    }

    /** Uma emenda empenhada em março é paga em outubro: reexecutar atualiza. */
    @Test
    void reexecucao_atualiza_os_valores_em_vez_de_duplicar() {
        repositorio.salvar(emenda("202541840004", LocalidadeEmenda.MUNICIPIO,
            "RECIFE", "PE", "0.00", "0.00"));
        repositorio.salvar(emenda("202541840004", LocalidadeEmenda.MUNICIPIO,
            "RECIFE", "PE", "1450000.00", "0.00"));

        assertThat(jdbc.sql("SELECT count(*) FROM emenda").query(Integer.class).single())
            .isEqualTo(1);
        assertThat(jdbc.sql("SELECT valor_pago FROM emenda WHERE codigo = ?")
                .param("202541840004").query(BigDecimal.class).single())
            .isEqualByComparingTo("1450000.00");
    }

    /**
     * Uma execução que não resolve o autor não pode APAGAR o vínculo que outra
     * — ou uma curadoria humana — estabeleceu.
     */
    @Test
    void reexecucao_sem_politico_nao_apaga_o_vinculo_ja_gravado() {
        Emenda comDono = emenda("202541840005", LocalidadeEmenda.MUNICIPIO,
            "OLINDA", "PE", "100.00", "0.00").comPolitico(comMandatoFederal);
        repositorio.salvar(comDono);

        repositorio.salvar(emenda("202541840005", LocalidadeEmenda.MUNICIPIO,
            "OLINDA", "PE", "200.00", "0.00"));

        assertThat(jdbc.sql("SELECT politico_id FROM emenda WHERE codigo = ?")
                .param("202541840005").query(UUID.class).single())
            .isEqualTo(comMandatoFederal);
    }

    /** 8,3% dos autores não são candidatos em 2026: a emenda entra sem dono. */
    @Test
    void emenda_sem_politico_e_gravada_e_nao_descartada() {
        repositorio.salvar(emenda("202541840006", LocalidadeEmenda.MUNICIPIO,
            "TIETE", "SP", "5000.00", "0.00"));

        var lido = jdbc.sql("SELECT politico_id, valor_pago FROM emenda WHERE codigo = ?")
            .param("202541840006").query().singleRow();
        assertThat(lido.get("politico_id")).isNull();
        assertThat((BigDecimal) lido.get("valor_pago")).isEqualByComparingTo("5000.00");
    }
}
