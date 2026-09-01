package br.org.votecomdados.ingestion.identidade;

import br.org.votecomdados.core.dominio.Enums.MotivoRejeicao;
import br.org.votecomdados.ingestion.execucao.Execucao;
import br.org.votecomdados.ingestion.staging.RepositorioDePayloadBruto;
import br.org.votecomdados.ingestion.staging.ServicoDeQuarentena;
import java.util.Iterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Carrega o cadastro de uma Casa e resolve a identidade de cada parlamentar.
 *
 * <p>Roda <b>depois</b> da coorte e <b>antes</b> dos votos — é o passo que
 * transforma "id 204554 da Câmara" em "esta pessoa", e sem ele todo voto
 * cairia em quarentena por FK.
 */
@Component
public class JobDeCadastroDeParlamentares {

    private static final Logger log =
        LoggerFactory.getLogger(JobDeCadastroDeParlamentares.class);

    private final LeitorDeDeputadosCamara leitor;
    private final ServicoDeResolucaoDeIdentidade resolucao;
    private final RepositorioDePayloadBruto staging;
    private final ServicoDeQuarentena quarentena;

    JobDeCadastroDeParlamentares(LeitorDeDeputadosCamara leitor,
                                 ServicoDeResolucaoDeIdentidade resolucao,
                                 RepositorioDePayloadBruto staging,
                                 ServicoDeQuarentena quarentena) {
        this.leitor = leitor;
        this.resolucao = resolucao;
        this.staging = staging;
        this.quarentena = quarentena;
    }

    /**
     * Como o registro bruto de uma Casa vira {@link ParlamentarDaCasa}.
     *
     * <p>O passo de resolução é idêntico em todas as fontes; o que muda é o
     * layout do cadastro. Manter o leitor como parâmetro é o que evita
     * duplicar a lógica de desfecho — que é justamente a parte onde um erro
     * atribuiria voto à pessoa errada.
     */
    @FunctionalInterface
    public interface LeitorDeCadastro {
        ParlamentarDaCasa ler(JsonNode registro);
    }

    public Resultado carregar(Execucao execucao, Iterator<JsonNode> linhas) {
        return carregar(execucao, linhas, leitor::ler);
    }

    public Resultado carregar(Execucao execucao, Iterator<JsonNode> linhas,
                              LeitorDeCadastro leitorDaCasa) {
        int resolvidos = 0, pendentes = 0, ambiguos = 0, foraDaCoorte = 0;

        while (linhas.hasNext()) {
            JsonNode linha = linhas.next();
            var parlamentar = leitorDaCasa.ler(linha);
            staging.gravar(execucao, "parlamentar", parlamentar.identificador(), linha);

            var vinculo = resolucao.resolver(parlamentar);
            switch (vinculo.desfecho()) {
                case RESOLVIDO -> {
                    resolucao.gravar(parlamentar, vinculo);
                    resolvidos++;
                }
                case PENDENTE_DE_CURADORIA -> {
                    // Gravado, mas marcado: existe para o curador olhar, não
                    // para a plataforma tratar como certo.
                    resolucao.gravar(parlamentar, vinculo);
                    pendentes++;
                }
                case AMBIGUO -> {
                    quarentena.rejeitar(execucao, "parlamentar",
                                        parlamentar.identificador(),
                                        MotivoRejeicao.POLITICO_NAO_RESOLVIDO,
                                        vinculo.detalhe(), linha);
                    ambiguos++;
                }
                case FORA_DA_COORTE -> {
                    // UMA linha por parlamentar, nunca por voto — e nunca
                    // alertada. Sem essa separação, a métrica de quarentena
                    // nasceria com dezenas de milhares de linhas e o alerta
                    // seria inútil no primeiro dia.
                    quarentena.rejeitar(execucao, "parlamentar",
                                        parlamentar.identificador(),
                                        MotivoRejeicao.FORA_DA_COORTE,
                                        vinculo.detalhe(), linha);
                    foraDaCoorte++;
                }
            }
        }

        log.info("cadastro de {}: {} resolvidos, {} pendentes de curadoria, "
                 + "{} ambiguos, {} fora da coorte",
                 execucao.fonte(), resolvidos, pendentes, ambiguos, foraDaCoorte);

        if (ambiguos > 0) {
            log.warn("{} parlamentar(es) ambiguo(s) aguardando curadoria: enquanto "
                     + "isso, os votos deles ficam em quarentena", ambiguos);
        }
        return new Resultado(resolvidos, pendentes, ambiguos, foraDaCoorte);
    }

    public record Resultado(int resolvidos, int pendentesDeCuradoria,
                            int ambiguos, int foraDaCoorte) {}
}
