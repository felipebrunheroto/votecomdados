package br.org.votecomdados.ingestion.emendas;

import br.org.votecomdados.core.dominio.Enums.LocalidadeEmenda;
import br.org.votecomdados.ingestion.armazenamento.ArmazenamentoDeObjetos;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Carrega as emendas de um ano e liga cada uma a quem a assinou.
 *
 * <h2>O dado vem do S3, não da API</h2>
 *
 * A API de Dados da CGU recusa requisição vinda de fora do Brasil. Medido em
 * 26/09/2026, mesma URL e mesma chave: 200 de máquina doméstica brasileira,
 * 401 do CloudShell em sa-east-1 (alcança, só quer a chave), e <b>504 desta
 * task em us-east-1</b> — reproduzido em duas execuções, com IPs distintos, e
 * também de runner do GitHub Actions. O 504 vem antes da autenticação: de
 * fora, mesmo SEM chave, a resposta é 504 quando o esperado seria 401.
 *
 * <p>Por isso a busca mora numa Lambda em São Paulo (infra/emendas.tf), que
 * grava o JSON do ano no bucket de ingestão. Aqui só se lê — o mesmo desenho
 * que o pacote do TSE já usava, e pela mesma razão: a fonte bloqueia a
 * máquina que precisa do dado.
 *
 * <h2>Emenda sem político NÃO é descartada</h2>
 *
 * Nossa base é a coorte de 2026, e 8,3% dos autores de 2025 não se
 * candidataram este ano. Descartar a emenda deles faria a página do município
 * mostrar menos dinheiro do que a cidade recebeu — mentira por omissão, que é
 * pior que lacuna declarada. Ela entra com {@code politico_id} nulo.
 */
@Component
public class JobDeEmendas {

    private static final Logger log = LoggerFactory.getLogger(JobDeEmendas.class);

    private final ArmazenamentoDeObjetos armazenamento;
    private final RepositorioDeEmendas repositorio;
    private final ObjectMapper json;
    private final String balde;

    JobDeEmendas(ArmazenamentoDeObjetos armazenamento, RepositorioDeEmendas repositorio,
                 ObjectMapper json,
                 @Value("${votecomdados.balde.ingestao:}") String balde) {
        this.armazenamento = armazenamento;
        this.repositorio = repositorio;
        this.json = json;
        this.balde = balde;
    }

    public record Resultado(
        int emendas, int comPolitico, int semPolitico,
        int autoresResolvidos, int autoresNaoEncontrados,
        int autoriaTransferida, int semCodigoDeAutor
    ) {}

    public Resultado carregar(int ano, Path diretorioDeTrabalho) {
        JsonNode linhas = lerDoBalde(ano, diretorioDeTrabalho);

        // Cache por código de autor: 6.311 emendas para ~628 autores, então
        // sem ele seriam dez consultas de casamento por autor, todas com o
        // mesmo resultado.
        Map<String, Optional<UUID>> porAutor = new HashMap<>();
        Map<LocalidadeEmenda, Integer> porLocalidade = new EnumMap<>(LocalidadeEmenda.class);

        int total = 0, comPolitico = 0, semCodigo = 0, transferidas = 0;
        int resolvidos = 0, naoEncontrados = 0;

        for (JsonNode linha : linhas) {
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
     * Traz o JSON do ano do bucket de ingestão.
     *
     * <p>A mensagem de erro nomeia o workflow que produz o arquivo. Sem isso,
     * quem encontrasse a falha veria só "objeto não existe" e teria de
     * descobrir sozinho que existe uma Lambda em outra região no caminho.
     */
    private JsonNode lerDoBalde(int ano, Path diretorioDeTrabalho) {
        if (balde == null || balde.isBlank()) {
            throw new IllegalStateException(
                "votecomdados.balde.ingestao nao configurado; sem ele nao ha "
                + "de onde ler o JSON das emendas");
        }
        String uri = "s3://" + balde + "/entrada/emendas-" + ano + ".json";

        // O download tem catch PROPRIO, e estreito. Envolver tambem o parse
        // faria a mensagem de "arquivo vazio" ser substituida pela de "nao
        // consegui ler" -- mandando quem depura olhar a Lambda quando o
        // problema esta no conteudo. E o mesmo defeito que fez a API
        // responder 500 no lugar de 404 (PR #74): catch largo demais engole
        // o sinal especifico.
        Path arquivo;
        try {
            arquivo = armazenamento.baixar(uri, diretorioDeTrabalho);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                "nao consegui ler " + uri + ". O arquivo e produzido pelo "
                + "workflow 'Buscar emendas (CGU)', que invoca a Lambda em "
                + "sa-east-1 -- a API da CGU recusa requisicao de fora do "
                + "Brasil, entao esta task nao pode busca-lo sozinha.", e);
        }

        JsonNode linhas;
        try {
            linhas = json.readTree(Files.readString(arquivo));
        } catch (IOException e) {
            throw new IllegalStateException("falha ao ler " + arquivo, e);
        }

        if (linhas == null || !linhas.isArray() || linhas.isEmpty()) {
            throw new IllegalStateException(
                uri + " existe mas nao tem emenda nenhuma; nada a carregar");
        }
        log.info("emendas {}: {} linha(s) lidas de {}", ano, linhas.size(), uri);
        return linhas;
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
