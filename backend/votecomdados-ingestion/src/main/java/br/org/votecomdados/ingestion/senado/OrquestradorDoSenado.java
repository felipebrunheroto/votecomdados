package br.org.votecomdados.ingestion.senado;

import br.org.votecomdados.ingestion.execucao.Execucao;
import br.org.votecomdados.ingestion.identidade.JobDeCadastroDeParlamentares;
import br.org.votecomdados.ingestion.identidade.LeitorDeSenadores;
import br.org.votecomdados.ingestion.publicacao.FinalizadorDeIngestao;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Cadastro e votações do Senado, na ordem que a FK de voto exige.
 *
 * <h2>Sem {@code Last-Modified}, o watermark vem do próprio dado</h2>
 *
 * A API do Senado não publica {@code Last-Modified} nem {@code ETag} — só
 * {@code cache-control: max-age=600} — então o padrão de {@code BaixadorDeArquivos}
 * (usado por Câmara e Alesp) não serve aqui: não há a quem perguntar "mudou?".
 * O ciclo baixa {@code /votacao?ano=<ano>} inteiro a cada execução — são
 * dezenas de votações por ano, não centenas de milhares de linhas — e o
 * watermark vira a maior {@code dataSessao} vista. Sem votação mais nova que
 * o watermark anterior, {@code houveMudanca} é {@code false} e o marcador não
 * avança: mover marcador sobre janela vazia é execução falha fingindo sucesso,
 * mesma regra do {@code JobIncremental}.
 *
 * <h2>O cadastro evita refazer 900 chamadas HTTP por dia</h2>
 *
 * O universo de parlamentares (legislaturas 50–57) tem ~900 pessoas, e cada
 * uma exige uma chamada própria para {@code DataNascimento}. Refazer as ~900
 * chamadas todo dia para buscar um dado <b>imutável</b> — data de nascimento
 * não muda — seria desperdício sem propósito. Antes de buscar detalhe, o
 * universo é filtrado contra quem já foi processado: já tem
 * {@code identificador_externo} (resolvido ou pendente de curadoria), ou já
 * está em {@code staging.registro_rejeitado} sem resolução
 * ({@code FORA_DA_COORTE} ou ambíguo). Só quem nunca passou por aqui gera uma
 * chamada nova. Um vínculo corrigido manualmente (curadoria marca
 * {@code resolvido_em}) volta a ser reprocessado no ciclo seguinte.
 */
@Component
public class OrquestradorDoSenado {

    private static final Logger log = LoggerFactory.getLogger(OrquestradorDoSenado.class);

    private final ClienteDoSenado cliente;
    private final JdbcClient jdbc;
    private final LeitorDeSenadores leitor;
    private final JobDeCadastroDeParlamentares cadastro;
    private final JobDoSenado job;
    private final FinalizadorDeIngestao finalizador;
    private final ObjectMapper json;

    OrquestradorDoSenado(ClienteDoSenado cliente, JdbcClient jdbc, LeitorDeSenadores leitor,
                        JobDeCadastroDeParlamentares cadastro, JobDoSenado job,
                        FinalizadorDeIngestao finalizador, ObjectMapper json) {
        this.cliente = cliente;
        this.jdbc = jdbc;
        this.leitor = leitor;
        this.cadastro = cadastro;
        this.job = job;
        this.finalizador = finalizador;
        this.json = json;
    }

    /** Endereços de produção — {@code EnderecosDoSenado.producao()}. */
    public Resultado executar(Execucao execucao, int ano, Instant desde) {
        return executar(execucao, ano, desde, EnderecosDoSenado.producao());
    }

    /**
     * @param enderecos parametrizado pelo mesmo motivo de
     *        {@link br.org.votecomdados.ingestion.alesp.OrquestradorDaAlesp.Enderecos}:
     *        sem isso não haveria como testar o ciclo contra HTTP real sem
     *        bater no Senado em produção.
     */
    public Resultado executar(Execucao execucao, int ano, Instant desde,
                              EnderecosDoSenado enderecos) {
        var cad = atualizarCadastro(execucao, enderecos);

        List<JsonNode> votacoesDoAno = new ArrayList<>();
        cliente.buscar(enderecos.votacoesDoAno(ano)).forEach(votacoesDoAno::add);

        LocalDate maiorData = votacoesDoAno.stream()
            .map(v -> texto(v, "dataSessao"))
            .filter(java.util.Objects::nonNull)
            .map(LocalDate::parse)
            .max(LocalDate::compareTo)
            .orElse(null);

        Instant watermarkCandidato = maiorData == null ? null
            : maiorData.atStartOfDay(ZoneOffset.UTC).toInstant();

        boolean houveMudanca = watermarkCandidato != null
            && (desde == null || watermarkCandidato.isAfter(desde));

        if (!houveMudanca) {
            log.info("senado: nenhuma votacao mais recente que {} em {}", desde, ano);
            return new Resultado(false, desde, cad.resolvidos(), cad.pendentesDeCuradoria(), 0, 0);
        }

        var votos = job.carregar(execucao, votacoesDoAno);

        finalizador.finalizar(execucao);

        log.info("senado: {} vinculos resolvidos, {} pendentes de curadoria, "
                 + "{} votacoes, {} votos", cad.resolvidos(), cad.pendentesDeCuradoria(),
                 votos.votacoes(), votos.votos());

        return new Resultado(true, watermarkCandidato, cad.resolvidos(),
                             cad.pendentesDeCuradoria(), votos.votacoes(), votos.votos());
    }

    // ------------------------------------------------------------------------
    // Cadastro
    // ------------------------------------------------------------------------

    private JobDeCadastroDeParlamentares.Resultado atualizarCadastro(
            Execucao execucao, EnderecosDoSenado enderecos) {
        List<JsonNode> universo = new ArrayList<>();
        var raiz = cliente.buscar(enderecos.universoDeParlamentares());
        parlamentares(raiz).forEach(universo::add);

        Set<String> jaProcessados = jaProcessados();

        var linhas = new ArrayList<JsonNode>();
        int reaproveitados = 0;
        for (JsonNode p : universo) {
            JsonNode ident = p.get("IdentificacaoParlamentar");
            String codigo = texto(ident, "CodigoParlamentar");
            if (codigo == null || jaProcessados.contains(codigo)) {
                reaproveitados++;
                continue;
            }
            linhas.add(achatar(codigo, ident, buscarDetalhe(codigo, enderecos)));
        }

        log.info("senado: {} no universo, {} ja processados (nao rebuscados), "
                 + "{} chamada(s) de detalhe nova(s)",
                 universo.size(), reaproveitados, linhas.size());

        return cadastro.carregar(execucao, linhas.iterator(), leitor::ler);
    }

    /** Quem já tem vínculo, e quem já foi rejeitado sem resolução em aberto. */
    private Set<String> jaProcessados() {
        var codigos = new HashSet<String>(jdbc.sql("""
                SELECT identificador FROM identificador_externo WHERE sistema = 'SENADO'
                """).query(String.class).list());
        codigos.addAll(jdbc.sql("""
                SELECT id_externo FROM staging.registro_rejeitado
                 WHERE fonte = 'SENADO' AND recurso = 'parlamentar' AND resolvido_em IS NULL
                """).query(String.class).list());
        return codigos;
    }

    private JsonNode buscarDetalhe(String codigo, EnderecosDoSenado enderecos) {
        var raiz = cliente.buscar(enderecos.detalheDoParlamentar(codigo));
        JsonNode parlamentar = raiz.path("DetalheParlamentar").path("Parlamentar");
        return parlamentar.isMissingNode() ? null : parlamentar;
    }

    /**
     * Monta o registro RASO que vai para {@code cadastro.carregar} — e que,
     * por ser ele (e não a resposta aninhada da API), é o que chega ao
     * staging depois da allowlist {@code SENADO:parlamentar}.
     *
     * <p>O detalhe, quando existe, tem prioridade sobre o universo: é dele que
     * vem {@code DataNascimento}, e o nome completo lá costuma ser o mais
     * atual. Sem detalhe (falha de rede pontual), o registro ainda entra —
     * sem data de nascimento a resolução cai no caminho por similaridade, mas
     * a pessoa não desaparece do ciclo.
     */
    private JsonNode achatar(String codigo, JsonNode identUniverso, JsonNode detalhe) {
        ObjectNode plano = json.createObjectNode();
        plano.put("codigoParlamentar", codigo);

        JsonNode identDetalhe = detalhe == null ? null : detalhe.get("IdentificacaoParlamentar");
        JsonNode dadosBasicos = detalhe == null ? null : detalhe.get("DadosBasicosParlamentar");

        copiarSeExistir(plano, "nomeParlamentar", identDetalhe, identUniverso, "NomeParlamentar");
        copiarSeExistir(plano, "nomeCompletoParlamentar", identDetalhe, identUniverso,
                        "NomeCompletoParlamentar");
        String uf = coalesce(texto(identDetalhe, "UfParlamentar"),
                             texto(identUniverso, "UfParlamentar"));
        if (uf != null) plano.put("ufParlamentar", uf);

        String nascimento = texto(dadosBasicos, "DataNascimento");
        if (nascimento != null) plano.put("dataNascimento", nascimento);

        return plano;
    }

    private static void copiarSeExistir(ObjectNode destino, String chaveDestino,
                                        JsonNode preferido, JsonNode fallback, String campo) {
        String v = coalesce(texto(preferido, campo), texto(fallback, campo));
        if (v != null) destino.put(chaveDestino, v);
    }

    private static Iterable<JsonNode> parlamentares(JsonNode raiz) {
        JsonNode lista = raiz.path("ListaParlamentarLegislatura")
                              .path("Parlamentares").path("Parlamentar");
        return lista.isArray() ? lista : List.of();
    }

    private static String coalesce(String... valores) {
        for (String v : valores) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static String texto(JsonNode no, String campo) {
        if (no == null) return null;
        JsonNode v = no.get(campo);
        if (v == null || v.isNull()) return null;
        String s = v.asString().trim();
        return s.isEmpty() ? null : s;
    }

    public record Resultado(boolean houveMudanca, Instant watermarkNovo,
                            int vinculosResolvidos, int vinculosPendentes,
                            int votacoes, int votos) {}
}
