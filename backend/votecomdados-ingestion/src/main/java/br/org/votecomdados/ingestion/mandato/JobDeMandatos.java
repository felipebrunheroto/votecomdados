package br.org.votecomdados.ingestion.mandato;

import br.org.votecomdados.core.dominio.Enums.CasaLegislativa;
import br.org.votecomdados.core.dominio.Enums.Fonte;
import br.org.votecomdados.core.dominio.Enums.MotivoRejeicao;
import br.org.votecomdados.ingestion.execucao.Execucao;
import br.org.votecomdados.ingestion.staging.ServicoDeQuarentena;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Carrega os períodos de exercício de quem está na coorte.
 *
 * <h2>Só a coorte, e isso não é economia — é escopo</h2>
 *
 * O plano previa ~7,9 mil chamadas, uma por deputado já cadastrado. Só que
 * ausência só é derivada para quem a plataforma exibe, e a plataforma exibe a
 * coorte. Buscar o histórico de quem não é candidato em 2026 gastaria a fonte
 * para produzir linhas que ninguém veria — e criaria dado pessoal fora do
 * escopo declarado.
 */
@Component
public class JobDeMandatos {

    private static final Logger log = LoggerFactory.getLogger(JobDeMandatos.class);

    private final ConstrutorDePeriodos construtor;
    private final RepositorioDeMandato repositorio;
    private final ServicoDeQuarentena quarentena;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    JobDeMandatos(ConstrutorDePeriodos construtor, RepositorioDeMandato repositorio,
                  ServicoDeQuarentena quarentena, JdbcClient jdbc, ObjectMapper json) {
        this.construtor = construtor;
        this.repositorio = repositorio;
        this.quarentena = quarentena;
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Identificadores da Casa que pertencem a alguém da coorte. */
    public List<String> identificadoresDaCoorte(Fonte fonte) {
        return jdbc.sql("""
                SELECT identificador FROM identificador_externo
                 WHERE sistema = :fonte::fonte_enum
                 ORDER BY identificador
                """).param("fonte", fonte.name()).query(String.class).list();
    }

    /**
     * Carrega o histórico de um parlamentar.
     *
     * @param eventos como a Casa devolve
     */
    public Resultado carregar(Execucao execucao, CasaLegislativa casa,
                              String identificador, Iterable<JsonNode> eventos) {
        var politicoId = politicoDe(execucao.fonte(), identificador);
        if (politicoId == null) {
            // Sem vínculo não há a quem atribuir o período. Não é erro deste
            // job: é o cadastro (W4) que ainda não resolveu esta pessoa.
            return new Resultado(0, 0);
        }

        Map<String, String> mapeamento = repositorio.mapeamentoDe(execucao.fonte());
        var normalizados = new ArrayList<RepositorioDeMandato.PeriodoNormalizado>();
        int naoMapeados = 0;

        for (PeriodoDeExercicio p : construtor.construir(eventos)) {
            String situacao = mapeamento.get(p.situacaoOrigem());
            if (situacao == null) {
                // Não se adivinha situação: marcar como exercício quem estava
                // licenciado inverteria o sentido do fato na página.
                quarentena.rejeitar(execucao, "exercicio_parlamentar", identificador,
                                    MotivoRejeicao.SITUACAO_NAO_MAPEADA,
                                    "situacao sem traducao: " + p.situacaoOrigem(),
                                    payloadDoPeriodo(p));
                naoMapeados++;
                continue;
            }
            normalizados.add(new RepositorioDeMandato.PeriodoNormalizado(
                situacao, p.situacaoOrigem(), condicaoNormalizada(p.condicaoOrigem()),
                p.inicio(), p.fim(), urlDe(execucao.fonte(), identificador)));
        }

        int gravados = repositorio.substituirPeriodos(politicoId, casa, normalizados);
        return new Resultado(gravados, naoMapeados);
    }

    /**
     * A Casa escreve "Titular"/"Suplente"; o enum é em caixa alta. Quando vem
     * nulo — acontece em eventos de convocação — o período fica sem condição,
     * que é mais honesto que inventar uma.
     */
    private static String condicaoNormalizada(String origem) {
        if (origem == null) return null;
        return switch (origem.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "titular" -> "TITULAR";
            case "suplente" -> "SUPLENTE";
            default -> null;
        };
    }

    /**
     * O que se sabe do período recusado, para o curador reprocessar.
     *
     * <p>Passa pela mesma allowlist do staging — quarentena não é exceção à
     * redação, e o caminho de erro é onde payload cru mais escapa.
     */
    private JsonNode payloadDoPeriodo(PeriodoDeExercicio p) {
        var no = json.createObjectNode();
        no.put("situacao", p.situacaoOrigem());
        no.put("condicaoEleitoral", p.condicaoOrigem());
        no.put("dataHora", p.inicio() == null ? null : p.inicio().toString());
        return no;
    }

    private UUID politicoDe(Fonte fonte, String identificador) {
        return jdbc.sql("""
                SELECT politico_id FROM identificador_externo
                 WHERE sistema = :fonte::fonte_enum AND identificador = :id
                """)
            .param("fonte", fonte.name()).param("id", identificador)
            .query(UUID.class).optional().orElse(null);
    }

    private static String urlDe(Fonte fonte, String identificador) {
        return fonte == Fonte.CAMARA
            ? "https://dadosabertos.camara.leg.br/api/v2/deputados/" + identificador
              + "/historico"
            : null;
    }

    public record Resultado(int periodos, int situacoesNaoMapeadas) {}
}
