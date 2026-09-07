package br.org.votecomdados.ingestion.massa;

import br.org.votecomdados.core.dominio.Enums.CasaLegislativa;
import br.org.votecomdados.ingestion.derivacao.DerivadorDeAusencia;
import br.org.votecomdados.ingestion.download.ArquivosDaCamara;
import br.org.votecomdados.ingestion.download.BaixadorDeArquivos;
import br.org.votecomdados.ingestion.download.CadastroDaCamara;
import br.org.votecomdados.ingestion.download.JobIncremental.EnderecosDoAno;
import br.org.votecomdados.ingestion.execucao.Execucao;
import br.org.votecomdados.ingestion.publicacao.FinalizadorDeIngestao;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private final CadastroDaCamara cadastro;
    private final JobDeBackfillCamara backfill;
    private final DerivadorDeAusencia derivador;
    private final FinalizadorDeIngestao finalizador;

    JobDeBackfill(BaixadorDeArquivos baixador, CadastroDaCamara cadastro, JobDeBackfillCamara backfill,
                 DerivadorDeAusencia derivador, FinalizadorDeIngestao finalizador) {
        this.baixador = baixador;
        this.cadastro = cadastro;
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

        // Uma vez, antes do primeiro ano: o cadastro da Camara e o arquivo
        // completo de todas as legislaturas, nao tem recorte por ano. E precisa
        // vir antes de qualquer materia -- o INSERT de proposicao so aceita
        // materia cujo autor ja esteja resolvido, entao um backfill sem esta
        // etapa carrega votacao e grava zero materia, sem erro nenhum.
        //
        // Sem condicional: o historico nunca foi lido, nao ha watermark a
        // perguntar -- a mesma razao de baixarAnoSemCondicional.
        cadastro.atualizar(execucao, trabalho, null,
                           enderecosPorAno.apply(anoInicial).deputados());

        Map<String, Long> tamanhosDoAnoAnterior = null;

        for (int ano = anoInicial; ano <= anoFinal; ano++) {
            log.info("backfill camara: carregando o ano {}", ano);
            var arquivos = baixarAnoSemCondicional(trabalho, enderecosPorAno.apply(ano));
            conferirQueNaoRepetiuOAnoAnterior(ano, tamanhosDoAnoAnterior, arquivos.tamanhos());
            tamanhosDoAnoAnterior = arquivos.tamanhos();

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
                                 votacoes.caminho(), votosBaixados.caminho(), maisRecente,
                                 Map.of("proposicoes", proposicoes.bytes(),
                                        "temas", temas.bytes(),
                                        "autores", autores.bytes(),
                                        "votacoes", votacoes.bytes(),
                                        "votos", votosBaixados.bytes()));
    }

    /**
     * Abaixo disto dois anos podem ter o mesmo tamanho por coincidência — um
     * CSV pequeno de um ano fraco em produção legislativa, por exemplo.
     */
    private static final long TAMANHO_MINIMO_PARA_DESCONFIAR = 1L << 20; // 1 MiB

    /**
     * O portal da Câmara já serviu o corpo de um ano sob a URL de outro.
     *
     * <p>Em 07/09/2026 o backfill morreu em 2004 com "unquoted carriage return
     * found in data", e o que o log mostrava era mais estranho que o erro:
     * {@code proposicoes-2003.csv} e {@code proposicoes-2004.csv} chegaram com
     * exatamente 26.469.441 bytes cada, {@code proposicoesAutores} com
     * 56.768.490 cada, {@code votacoes} com 4.889.165 cada — com
     * {@code Last-Modified} diferentes. Baixados de novo horas depois, os
     * tamanhos eram outros e o COPY passava. Foi o portal entregando conteúdo
     * errado durante uma regeneração, não dado corrompido na origem.
     *
     * <p>Naquele dia demos sorte: o corpo trocado quebrou o parser. Se tivesse
     * carregado, teríamos gravado matéria de 2003 rotulada como 2004 — dado
     * errado, em silêncio, num site cujo propósito é ser confiável. Por isso a
     * checagem falha em vez de avisar.
     *
     * <p>Dois arquivos grandes coincidindo ao byte entre anos consecutivos é o
     * suficiente: um só ainda pode ser acaso, dois não são.
     */
    // Visível ao pacote de propósito: os goldens da suíte são amostras de
    // poucos KB, abaixo do limite, então nenhum teste de ponta a ponta
    // exercitaria esta regra sem carregar megabytes de fixture só para isso.
    static void conferirQueNaoRepetiuOAnoAnterior(
            int ano, Map<String, Long> anterior, Map<String, Long> atual) {
        if (anterior == null) return;

        var repetidos = atual.entrySet().stream()
            .filter(e -> e.getValue() >= TAMANHO_MINIMO_PARA_DESCONFIAR)
            .filter(e -> e.getValue().equals(anterior.get(e.getKey())))
            .map(Map.Entry::getKey)
            .sorted()
            .toList();

        if (repetidos.size() >= 2) {
            throw new IllegalStateException(
                "a fonte entregou para " + ano + " arquivo(s) identicos aos de "
                + (ano - 1) + " " + repetidos + "; provavelmente o portal servia "
                + "conteudo de outro ano durante uma regeneracao. Carregar isso "
                + "gravaria dado de " + (ano - 1) + " rotulado como " + ano
                + ". Repita o backfill a partir de --desde=" + ano + " mais tarde");
        }
    }

    private BaixadorDeArquivos.ArquivoBaixado baixar(Path trabalho, String nome,
                                                      java.net.URI origem) {
        return baixador.baixarSeMudou(origem, trabalho.resolve(nome + ".csv"), null)
            .orElseThrow(() -> new IllegalStateException(
                "fonte recusou entregar " + origem + " (sem condicional, 304 nao era esperado)"));
    }

    private record ArquivosDoAno(Path proposicoes, Path temas, Path autores,
                                 Path votacoes, Path votos, Instant maisRecente,
                                 Map<String, Long> tamanhos) {}

    /**
     * @param anosProcessados na ordem em que entraram — útil para conferir
     *        que uma retomada não pulou nem repetiu ano
     */
    public record Resultado(List<Integer> anosProcessados, Instant watermarkNovo,
                            int materias, int votacoes, int votos) {}
}
