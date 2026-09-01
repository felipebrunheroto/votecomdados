package br.org.votecomdados.ingestion.alesp;

import br.org.votecomdados.ingestion.download.BaixadorDeArquivos;
import br.org.votecomdados.ingestion.execucao.Execucao;
import br.org.votecomdados.ingestion.identidade.JobDeCadastroDeParlamentares;
import br.org.votecomdados.ingestion.publicacao.FinalizadorDeIngestao;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Baixa os arquivos da Alesp e roda a carga na ordem que as FKs exigem.
 *
 * <h2>A Alesp não tem recorte por ano, e isso muda o incremental</h2>
 *
 * A Câmara publica {@code votacoes-2026.csv}; a Alesp publica a série inteira
 * num arquivo só, regerado <b>todo dia</b>. Não há como pedir "só o que mudou",
 * então o incremental depende inteiramente do {@code If-Modified-Since} — que
 * a fonte respeita, e que dispensa baixar 350 MB quando nada mudou.
 *
 * <h2>A ordem não é preferência, é integridade referencial</h2>
 *
 * <ol>
 *   <li><b>Cadastro</b> — sem vínculo, todo voto seria descartado por não
 *       resolver a pessoa;</li>
 *   <li><b>Naturezas</b> — sem elas {@code sigla_tipo} seria um número;</li>
 *   <li><b>Proposituras</b> — a autoria e a votação as referenciam;</li>
 *   <li><b>Autoria</b>;</li>
 *   <li><b>Reuniões</b> — é o arquivo que data a votação;</li>
 *   <li><b>Votos</b>.</li>
 * </ol>
 */
@Component
public class OrquestradorDaAlesp {

    private static final Logger log = LoggerFactory.getLogger(OrquestradorDaAlesp.class);

    private final BaixadorDeArquivos baixador;
    private final LeitorDeXmlAlesp leitor;
    private final LeitorDeDeputadosAlesp leitorDeCadastro;
    private final JobDeCadastroDeParlamentares cadastro;
    private final JobDaAlesp job;
    private final FinalizadorDeIngestao finalizador;

    OrquestradorDaAlesp(BaixadorDeArquivos baixador, LeitorDeXmlAlesp leitor,
                        LeitorDeDeputadosAlesp leitorDeCadastro,
                        JobDeCadastroDeParlamentares cadastro, JobDaAlesp job,
                        FinalizadorDeIngestao finalizador) {
        this.baixador = baixador;
        this.leitor = leitor;
        this.leitorDeCadastro = leitorDeCadastro;
        this.cadastro = cadastro;
        this.job = job;
        this.finalizador = finalizador;
    }

    /** Endereços de produção — {@code ArquivosDaAlesp}. */
    public Resultado executar(Execucao execucao, Path trabalho, Instant desde) {
        return executar(execucao, trabalho, desde, Enderecos.daAlesp());
    }

    /**
     * @param enderecos os seis endereços a consultar. Parametrizado — e não
     *        fixo em {@code ArquivosDaAlesp} — pelo mesmo motivo de
     *        {@link br.org.votecomdados.ingestion.download.JobIncremental}:
     *        sem isso não haveria como testar o ciclo inteiro contra HTTP
     *        real sem bater no portal da Alesp em produção.
     */
    public Resultado executar(Execucao execucao, Path trabalho, Instant desde,
                              Enderecos enderecos) {
        var arquivos = new LinkedHashMap<String, URI>();
        arquivos.put("deputados.xml", enderecos.deputados());
        arquivos.put("naturezasSpl.xml", enderecos.naturezas());
        arquivos.put("proposituras.zip", enderecos.proposituras());
        arquivos.put("documento_autor.zip", enderecos.autoria());
        arquivos.put("comissoes_permanentes_reunioes.xml", enderecos.reunioes());
        arquivos.put("comissoes_permanentes_votacoes.xml", enderecos.votacoes());

        var baixados = new LinkedHashMap<String, Path>();
        Instant watermark = desde;

        for (var e : arquivos.entrySet()) {
            Path destino = trabalho.resolve(e.getKey());
            Optional<BaixadorDeArquivos.ArquivoBaixado> r =
                baixador.baixarSeMudou(e.getValue(), destino, desde);
            if (r.isEmpty()) continue;
            baixados.put(e.getKey(), r.get().caminho());
            if (watermark == null || r.get().modificadoEm().isAfter(watermark)) {
                watermark = r.get().modificadoEm();
            }
        }

        if (baixados.isEmpty()) {
            log.info("alesp: nenhum arquivo mudou desde {}", desde);
            return new Resultado(false, desde, 0, 0, 0);
        }

        // Um arquivo pode não ter mudado enquanto outro mudou. A carga precisa
        // dos seis, então o que não veio agora é lido do ciclo anterior — e se
        // não existir localmente, é baixado sem condicional.
        for (var e : arquivos.entrySet()) {
            baixados.computeIfAbsent(e.getKey(), nome -> {
                Path destino = trabalho.resolve(nome);
                return baixador.baixarSeMudou(e.getValue(), destino, null)
                    .orElseThrow(() -> new IllegalStateException(
                        "a fonte respondeu 304 para um arquivo que nao temos: " + nome))
                    .caminho();
            });
        }

        var cad = cadastro.carregar(execucao, ler(baixados.get("deputados.xml"), "Deputado")
                                                  .iterator(), leitorDeCadastro::ler);

        Map<String, String> naturezas;
        try (var fluxo = leitor.ler(baixados.get("naturezasSpl.xml"), "natureza")) {
            naturezas = job.lerNaturezas(fluxo);
        }

        JobDaAlesp.Resultado props;
        try (var fluxo = leitor.lerDoZip(baixados.get("proposituras.zip"), "propositura")) {
            props = job.carregarProposituras(execucao, fluxo, naturezas);
        }

        int autorias;
        try (var fluxo = leitor.lerDoZip(baixados.get("documento_autor.zip"),
                                         "DocumentoAutor")) {
            autorias = job.carregarAutoria(execucao, fluxo);
        }

        Map<String, java.time.LocalDate> datas;
        try (var fluxo = leitor.ler(baixados.get("comissoes_permanentes_reunioes.xml"),
                                    "ReuniaoComissao")) {
            datas = job.lerReunioes(execucao, fluxo);
        }

        JobDaAlesp.ResultadoVotacoes votos;
        try (var fluxo = leitor.ler(baixados.get("comissoes_permanentes_votacoes.xml"),
                                    "ReuniaoComissaoVotacao")) {
            votos = job.carregarVotos(execucao, fluxo, datas);
        }

        log.info("alesp: {} vinculos resolvidos, {} pendentes de curadoria, "
                 + "{} proposituras, {} autorias, {} votacoes, {} votos",
                 cad.resolvidos(), cad.pendentesDeCuradoria(), props.gravadas(),
                 autorias, votos.votacoes(), votos.votos());

        // Sem isto o achado A1 se repete: a Alesp é a única das três fontes
        // que carregava dado sem nunca fechar a ingestão. `possui_atuacao_legislativa`
        // e `perfil_leitura` ficavam com o estado de ANTES desta execução.
        finalizador.finalizar(execucao);

        return new Resultado(true, watermark, props.gravadas(), votos.votacoes(),
                             votos.votos());
    }

    /** O cadastro tem 94 registros; materializar não pesa e simplifica a leitura. */
    private List<JsonNode> ler(Path arquivo, String elemento) {
        try (var fluxo = leitor.ler(arquivo, elemento)) {
            var lista = new ArrayList<JsonNode>();
            fluxo.forEach(lista::add);
            return lista;
        }
    }

    public record Resultado(boolean houveMudanca, Instant watermarkNovo,
                            int proposituras, int votacoes, int votos) {}

    /** Endereços dos seis arquivos que a carga precisa. */
    public record Enderecos(URI deputados, URI naturezas, URI proposituras,
                            URI autoria, URI reunioes, URI votacoes) {

        public static Enderecos daAlesp() {
            return new Enderecos(
                ArquivosDaAlesp.deputados(), ArquivosDaAlesp.naturezas(),
                ArquivosDaAlesp.proposituras(), ArquivosDaAlesp.autoria(),
                ArquivosDaAlesp.reunioesDeComissao(), ArquivosDaAlesp.votacoesDeComissao());
        }
    }
}
