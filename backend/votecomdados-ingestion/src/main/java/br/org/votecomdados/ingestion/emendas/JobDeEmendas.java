package br.org.votecomdados.ingestion.emendas;

import br.org.votecomdados.core.dominio.Enums.LocalidadeEmenda;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Carrega as emendas de um ano e liga cada uma a quem a assinou.
 *
 * <h2>Emenda sem político NÃO é descartada</h2>
 *
 * Nossa base é a coorte de 2026, e 8,3% dos autores de 2025 não se
 * candidataram este ano. Descartar a emenda deles faria a página do município
 * mostrar menos dinheiro do que a cidade recebeu — mentira por omissão, que é
 * pior que lacuna declarada. Ela entra com {@code politico_id} nulo.
 *
 * <h2>O resumo relata o que NÃO deu certo</h2>
 *
 * Um job que só diz "6.311 emendas carregadas" esconde o que interessa. O
 * relatório separa vínculo resolvido de autor não encontrado, e quebra o
 * dinheiro por forma de localidade — porque o número que decide a
 * funcionalidade é a fração do valor que tem cidade, e ela precisa estar
 * visível a cada execução, não só no dia do spike.
 */
@Component
public class JobDeEmendas {

    private static final Logger log = LoggerFactory.getLogger(JobDeEmendas.class);

    /** Teto de segurança: 2025 teve 421 páginas; 2.000 é folga com margem. */
    private static final int MAX_PAGINAS = 2_000;

    private final ClienteDoPortal cliente;
    private final RepositorioDeEmendas repositorio;

    JobDeEmendas(ClienteDoPortal cliente, RepositorioDeEmendas repositorio) {
        this.cliente = cliente;
        this.repositorio = repositorio;
    }

    public record Resultado(
        int emendas, int comPolitico, int semPolitico,
        int autoresResolvidos, int autoresNaoEncontrados,
        int autoriaTransferida, int semCodigoDeAutor
    ) {}

    public Resultado carregar(int ano) {
        // Cache por código de autor: 6.311 emendas para ~628 autores, então
        // sem ele seriam dez consultas de casamento por autor, todas com o
        // mesmo resultado.
        Map<String, Optional<UUID>> porAutor = new HashMap<>();
        Map<LocalidadeEmenda, Integer> porLocalidade = new HashMap<>();

        int total = 0, comPolitico = 0, semCodigo = 0, transferidas = 0;
        int resolvidos = 0, naoEncontrados = 0;

        for (int pagina = 1; pagina <= MAX_PAGINAS; pagina++) {
            JsonNode lote = cliente.pagina(ano, pagina);
            if (lote == null || lote.isEmpty()) break;

            for (JsonNode linha : lote) {
                Emenda e = Emenda.de(linha);
                if (e.codigo() == null) continue;

                if (e.codigoAutor() == null) {
                    // Sem código confiável não há âncora. A emenda entra assim
                    // mesmo — o valor dela conta para o município —, mas sem
                    // dono, porque inventar dono é pior.
                    semCodigo++;
                } else {
                    final String nome = e.autorNome();
                    Optional<UUID> politico = porAutor.computeIfAbsent(
                        e.codigoAutor(), cod -> resolver(cod, nome));
                    if (politico.isPresent()) {
                        e = e.comPolitico(politico.get());
                        comPolitico++;
                    }
                }
                if (e.autorOrigemNome() != null) transferidas++;
                porLocalidade.merge(e.localidadeTipo(), 1, Integer::sum);

                repositorio.salvar(e);
                total++;
            }
        }

        for (var v : porAutor.values()) {
            if (v.isPresent()) resolvidos++; else naoEncontrados++;
        }

        log.info("emendas {}: {} carregadas | {} com politico, {} sem", ano,
            total, comPolitico, total - comPolitico);
        log.info("emendas {}: autores {} resolvidos, {} nao encontrados "
            + "(fora da coorte de 2026 ou variante de nome)",
            ano, resolvidos, naoEncontrados);
        if (transferidas > 0) {
            log.info("emendas {}: {} com autoria transferida de ex-parlamentar",
                ano, transferidas);
        }
        if (semCodigo > 0) {
            log.warn("emendas {}: {} sem codigo de autor confiavel", ano, semCodigo);
        }
        log.info("emendas {}: por localidade {}", ano, porLocalidade);

        return new Resultado(total, comPolitico, total - comPolitico,
            resolvidos, naoEncontrados, transferidas, semCodigo);
    }

    /**
     * Vínculo já gravado primeiro; casamento por nome só quando não há.
     *
     * <p>A ordem importa: uma vez resolvido, o código é a âncora. Recasar nome
     * a cada execução convidaria o vínculo a mudar sozinho quando a base muda
     * — e vínculo que oscila é pior que vínculo ausente.
     */
    private Optional<UUID> resolver(String codigoAutor, String autorNome) {
        Optional<UUID> conhecido = repositorio.vinculoConhecido(codigoAutor);
        if (conhecido.isPresent()) return conhecido;

        Optional<UUID> casado = repositorio.casarPorNome(autorNome);
        casado.ifPresent(id -> repositorio.gravarVinculo(codigoAutor, id));
        return casado;
    }
}
