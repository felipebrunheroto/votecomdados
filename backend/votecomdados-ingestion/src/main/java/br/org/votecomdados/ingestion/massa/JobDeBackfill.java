package br.org.votecomdados.ingestion.massa;

import br.org.votecomdados.core.dominio.Enums.CasaLegislativa;
import br.org.votecomdados.ingestion.derivacao.DerivadorDeAusencia;
import br.org.votecomdados.ingestion.download.ArquivosDaCamara;
import br.org.votecomdados.ingestion.download.BaixadorDeArquivos;
import br.org.votecomdados.ingestion.download.JobIncremental.EnderecosDoAno;
import br.org.votecomdados.ingestion.execucao.Execucao;
import br.org.votecomdados.ingestion.publicacao.FinalizadorDeIngestao;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Orquestra {@link JobDeBackfillCamara} por ano — a peça que faltava desde o
 * W6: as cargas por ano existem e são testadas, mas nada as encadeava numa
 * série histórica.
 *
 * <h2>Não é {@link br.org.votecomdados.ingestion.download.JobIncremental} num laço</h2>
 *
 * Reaproveitar o incremental ano a ano pareceria natural e estaria errado em
 * dois pontos:
 *
 * <ul>
 *   <li><b>Download condicional não se aplica.</b> O incremental pergunta "a
 *       fonte mudou desde ontem?" — aqui a pergunta não faz sentido: o
 *       histórico nunca foi lido, então cada ano é baixado sem condicional.</li>
 *   <li><b>Derivar e finalizar por ano seria 25 varreduras completas para
 *       publicar o mesmo estado final</b> — e pior, a derivação
 *       <b>intermediária estaria errada</b>: ela cruza votação com mandato, e
 *       enquanto faltam anos o universo de quem estava em exercício está
 *       incompleto. Aqui os dois rodam <b>uma vez</b>, depois que todo ano
 *       pedido já foi carregado.</li>
 * </ul>
 *
 * <h2>Retomada é parâmetro, não estado rastreado</h2>
 *
 * Um backfill de 25 anos que morre no ano 19 não pode recomeçar do zero. A
 * saída não é uma tabela nova de progresso — é o operador poder pedir
 * {@code --desde=2019}. Isso é seguro porque cada ano é upsert idempotente:
 * pedir um {@code desde} anterior ao necessário só reprocessa anos que já
 * estavam certos, sem duplicar nem corromper nada.
 */
@Component
public class JobDeBackfill {

    private static final Logger log = LoggerFactory.getLogger(JobDeBackfill.class);

    /**
     * A Câmara não publica voto nominal individual antes de 2001 (achado do
     * spike original, registrado em {@code cobertura_fonte}). Backfill antes
     * disso carregaria proposição e votação simbólica sem nenhum voto — dado
     * incompleto que pareceria completo.
     */
    public static final int PRIMEIRO_ANO_COM_VOTO_NOMINAL = 2001;

    private final BaixadorDeArquivos baixador;
    private final JobDeBackfillCamara backfill;
    private final DerivadorDeAusencia derivador;
    private final FinalizadorDeIngestao finalizador;

    JobDeBackfill(BaixadorDeArquivos baixador, JobDeBackfillCamara backfill,
                 DerivadorDeAusencia derivador, FinalizadorDeIngestao finalizador) {
        this.baixador = baixador;
        this.backfill = backfill;
        this.derivador = derivador;
        this.finalizador = finalizador;
    }

    /** Endereços de produção — {@link ArquivosDaCamara}. */
    public Resultado executar(Execucao execucao, int anoInicial, int anoFinal, Path trabalho) {
        return executar(execucao, anoInicial, anoFinal, trabalho, EnderecosDoAno::daCamara);
    }

    /**
     * @param enderecosPorAno parametrizado pelo mesmo motivo de
     *        {@code OrquestradorDaAlesp.Enderecos}: sem isso não haveria como
     *        testar a série sem baixar 25 anos da Câmara em produção a cada
     *        build. Em produção é sempre {@link ArquivosDaCamara}.
     */
    public Resultado executar(Execucao execucao, int anoInicial, int anoFinal, Path trabalho,
                              IntFunction<EnderecosDoAno> enderecosPorAno) {
        if (anoInicial > anoFinal) {
            throw new IllegalArgumentException(
                "--desde=" + anoInicial + " e posterior a --ate=" + anoFinal);
        }

        Instant watermark = execucao.watermarkAnterior();
        int materias = 0, votacoes = 0, votos = 0;
        var anosProcessados = new ArrayList<Integer>();

        for (int ano = anoInicial; ano <= anoFinal; ano++) {
            log.info("backfill camara: carregando o ano {}", ano);
            var arquivos = baixarAnoSemCondicional(trabalho, enderecosPorAno.apply(ano));

            var p = backfill.carregarProposicoes(execucao, arquivos.proposicoes(),
                                                 arquivos.temas(), arquivos.autores());
            materias += p.materias();

            // Votação antes de voto: o voto referencia a votação por FK.
            votacoes += backfill.carregarVotacoes(execucao, arquivos.votacoes());
            votos += backfill.carregarVotos(execucao, arquivos.votos()).gravados();

            anosProcessados.add(ano);
            if (watermark == null || arquivos.maisRecente().isAfter(watermark)) {
                watermark = arquivos.maisRecente();
            }
        }

        // Uma vez, depois de TODO ano pedido carregado — nunca no meio do
        // laço. Ver a javadoc da classe.
        derivador.derivar(execucao, CasaLegislativa.CAMARA);
        finalizador.finalizar(execucao);

        log.info("backfill camara concluido: anos {}-{}, {} materias, {} votacoes, {} votos",
                 anoInicial, anoFinal, materias, votacoes, votos);

        return new Resultado(List.copyOf(anosProcessados), watermark, materias, votacoes, votos);
    }

    /**
     * Baixa os cinco arquivos do ano sem {@code If-Modified-Since}: o ano
     * nunca foi lido, então a pergunta "mudou desde quando" não se aplica —
     * ao contrário do incremental, aqui SEMPRE se baixa.
     */
    private ArquivosDoAno baixarAnoSemCondicional(Path trabalho, EnderecosDoAno enderecos) {
        var proposicoes = baixar(trabalho, "proposicoes", enderecos.proposicoes());
        var temas = baixar(trabalho, "temas", enderecos.temas());
        var autores = baixar(trabalho, "autores", enderecos.autores());
        var votacoes = baixar(trabalho, "votacoes", enderecos.votacoes());
        var votosBaixados = baixar(trabalho, "votos", enderecos.votos());

        Instant maisRecente = List.of(proposicoes, temas, autores, votacoes, votosBaixados)
            .stream().map(BaixadorDeArquivos.ArquivoBaixado::modificadoEm)
            .max(Instant::compareTo).orElseThrow();

        return new ArquivosDoAno(proposicoes.caminho(), temas.caminho(), autores.caminho(),
                                 votacoes.caminho(), votosBaixados.caminho(), maisRecente);
    }

    private BaixadorDeArquivos.ArquivoBaixado baixar(Path trabalho, String nome,
                                                      java.net.URI origem) {
        return baixador.baixarSeMudou(origem, trabalho.resolve(nome + ".csv"), null)
            .orElseThrow(() -> new IllegalStateException(
                "fonte recusou entregar " + origem + " (sem condicional, 304 nao era esperado)"));
    }

    private record ArquivosDoAno(Path proposicoes, Path temas, Path autores,
                                 Path votacoes, Path votos, Instant maisRecente) {}

    /**
     * @param anosProcessados na ordem em que entraram — útil para conferir
     *        que uma retomada não pulou nem repetiu ano
     */
    public record Resultado(List<Integer> anosProcessados, Instant watermarkNovo,
                            int materias, int votacoes, int votos) {}
}
