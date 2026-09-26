package br.org.votecomdados.ingestion.emendas;

import br.org.votecomdados.core.dominio.Enums.Fonte;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Grava emendas e resolve de quem elas são.
 *
 * <h2>Por que não reusar {@code ServicoDeResolucaoDeIdentidade}</h2>
 *
 * Aquele serviço casa por nome civil <b>mais data de nascimento</b>, e cai em
 * similaridade ancorada por <b>UF</b> quando falta a data. A API de emendas
 * não traz nenhum dos três: só o nome. Usá-lo aqui mandaria os 592 autores
 * inteiros para curadoria manual — vinte e cinco vezes a fila que hoje existe.
 *
 * <h2>A âncora que substitui a data de nascimento</h2>
 *
 * Quem assina emenda individual <b>exerceu mandato federal</b>. Isso é
 * informação que já temos, e restringir o casamento a esse conjunto é uma
 * âncora de verdade, não um atalho.
 *
 * <p>Medido sobre os 592 autores de 2025:
 *
 * <pre>
 *                       base inteira    só com mandato federal
 *   vínculo 1:1              448                452
 *   ambíguo                   20                  1
 * </pre>
 *
 * A âncora não apenas evita erro: ela <b>resolve</b> ambiguidade — quatro
 * nomes que empatavam contra as 20.874 pessoas ficam únicos entre as 1.067 com
 * mandato federal. E os quinze que deixaram de casar casavam com gente sem
 * mandato federal algum: eram casamentos errados, recusados.
 */
@Component
public class RepositorioDeEmendas {

    private final JdbcClient jdbc;

    RepositorioDeEmendas(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Vínculo já estabelecido para este código de autor.
     *
     * <p>Consultado antes de qualquer casamento por nome: uma vez resolvido, o
     * código é a âncora, e recasar nome a cada ingestão convidaria o resultado
     * a mudar sozinho entre execuções.
     */
    public Optional<UUID> vinculoConhecido(String codigoAutor) {
        return jdbc.sql("""
                SELECT politico_id FROM identificador_externo
                 WHERE sistema = :sistema::fonte_enum AND identificador = :id
                """)
            .param("sistema", Fonte.PORTAL_TRANSPARENCIA.name())
            .param("id", codigoAutor)
            .query(UUID.class).optional();
    }

    /**
     * Casa o nome do autor contra quem tem mandato federal registrado.
     *
     * <p>Compara contra as três formas de nome que guardamos — civil, de urna
     * e parlamentar — porque a CGU não diz qual delas usa, e na prática usa as
     * três. Devolve vazio tanto quando ninguém casa quanto quando mais de um
     * casa: <b>empate não se desempata por regra</b>. Atribuir uma emenda à
     * pessoa errada é pior que não atribuí-la, e o único caso ambíguo medido em
     * 2025 fica sem vínculo em vez de virar aposta.
     */
    public Optional<UUID> casarPorNome(String autorNome) {
        List<UUID> achados = jdbc.sql("""
                SELECT DISTINCT p.id
                  FROM politico p
                  JOIN identificador_externo ie ON ie.politico_id = p.id
                 WHERE ie.sistema IN ('CAMARA', 'SENADO')
                   AND unaccent_imutavel(upper(:nome)) IN (
                         unaccent_imutavel(upper(p.nome_civil)),
                         unaccent_imutavel(upper(coalesce(p.nome_urna, ''))),
                         unaccent_imutavel(upper(coalesce(p.nome_parlamentar, '')))
                       )
                """)
            .param("nome", autorNome)
            .query(UUID.class).list();

        return achados.size() == 1 ? Optional.of(achados.getFirst()) : Optional.empty();
    }

    /** Registra o vínculo para que a próxima execução não precise recasar. */
    public void gravarVinculo(String codigoAutor, UUID politicoId) {
        jdbc.sql("""
                INSERT INTO identificador_externo
                       (politico_id, sistema, identificador, metodo_resolucao)
                VALUES (:politico, :sistema::fonte_enum, :id, 'DETERMINISTICO')
                ON CONFLICT (sistema, identificador) DO NOTHING
                """)
            .param("politico", politicoId)
            .param("sistema", Fonte.PORTAL_TRANSPARENCIA.name())
            .param("id", codigoAutor)
            .update();
    }

    /**
     * Insere ou atualiza uma emenda.
     *
     * <p>O upsert é por {@code codigo}, que é estável. Os valores mudam ao
     * longo do ano — uma emenda empenhada em março pode ser paga em outubro —,
     * então reexecutar a ingestão precisa ATUALIZAR, não duplicar nem ignorar.
     *
     * <p>{@code politico_id} usa {@code coalesce} com o valor já gravado: se
     * uma execução não conseguir resolver o autor, ela não pode APAGAR um
     * vínculo que uma execução anterior — ou uma curadoria humana —
     * estabeleceu.
     */
    public void salvar(Emenda e) {
        jdbc.sql("""
                INSERT INTO emenda (
                    codigo, ano, numero, tipo,
                    codigo_autor, autor_nome, autor_origem_nome, politico_id,
                    localidade_bruta, localidade_tipo, municipio_nome, uf,
                    funcao, subfuncao,
                    valor_empenhado, valor_liquidado, valor_pago,
                    valor_resto_inscrito, valor_resto_cancelado, valor_resto_pago
                ) VALUES (
                    :codigo, :ano, :numero, :tipo,
                    :codigoAutor, :autorNome, :autorOrigemNome, :politicoId,
                    :localidadeBruta, :localidadeTipo::localidade_emenda_enum,
                    :municipio, :uf,
                    :funcao, :subfuncao,
                    :empenhado, :liquidado, :pago,
                    :restoInscrito, :restoCancelado, :restoPago
                )
                ON CONFLICT (codigo) DO UPDATE SET
                    tipo              = EXCLUDED.tipo,
                    autor_nome        = EXCLUDED.autor_nome,
                    autor_origem_nome = EXCLUDED.autor_origem_nome,
                    politico_id       = coalesce(EXCLUDED.politico_id, emenda.politico_id),
                    localidade_bruta  = EXCLUDED.localidade_bruta,
                    localidade_tipo   = EXCLUDED.localidade_tipo,
                    municipio_nome    = EXCLUDED.municipio_nome,
                    uf                = EXCLUDED.uf,
                    funcao            = EXCLUDED.funcao,
                    subfuncao         = EXCLUDED.subfuncao,
                    valor_empenhado   = EXCLUDED.valor_empenhado,
                    valor_liquidado   = EXCLUDED.valor_liquidado,
                    valor_pago        = EXCLUDED.valor_pago,
                    valor_resto_inscrito  = EXCLUDED.valor_resto_inscrito,
                    valor_resto_cancelado = EXCLUDED.valor_resto_cancelado,
                    valor_resto_pago      = EXCLUDED.valor_resto_pago,
                    coletado_em       = now()
                """)
            .param("codigo", e.codigo()).param("ano", e.ano())
            .param("numero", e.numero()).param("tipo", e.tipo())
            .param("codigoAutor", e.codigoAutor()).param("autorNome", e.autorNome())
            .param("autorOrigemNome", e.autorOrigemNome()).param("politicoId", e.politicoId())
            .param("localidadeBruta", e.localidadeBruta())
            .param("localidadeTipo", e.localidadeTipo().name())
            .param("municipio", e.municipioNome()).param("uf", e.uf())
            .param("funcao", e.funcao()).param("subfuncao", e.subfuncao())
            .param("empenhado", e.valorEmpenhado()).param("liquidado", e.valorLiquidado())
            .param("pago", e.valorPago())
            .param("restoInscrito", e.valorRestoInscrito())
            .param("restoCancelado", e.valorRestoCancelado())
            .param("restoPago", e.valorRestoPago())
            .update();
    }
}
