package br.org.votecomdados.ingestion.download;

import br.org.votecomdados.core.dominio.Enums.CasaLegislativa;
import br.org.votecomdados.ingestion.derivacao.DerivadorDeAusencia;
import br.org.votecomdados.ingestion.execucao.Execucao;
import br.org.votecomdados.ingestion.massa.JobDeBackfillCamara;
import br.org.votecomdados.ingestion.publicacao.FinalizadorDeIngestao;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * O ciclo diário da Câmara.
 *
 * <h2>Por que não é REST</h2>
 *
 * A arquitetura previa "arquivos em massa no backfill, REST no incremental".
 * A verificação mostrou que a distinção não se justifica <b>nesta fonte</b>: os
 * arquivos anuais da Câmara são regravados diariamente e respondem {@code 304}
 * a {@code If-Modified-Since}. O incremental é então o mesmo caminho do
 * backfill, restrito ao ano corrente — e quando nada mudou, a resposta tem zero
 * byte.
 *
 * <p>O que se ganha: um caminho de código em vez de dois, e nenhuma máquina de
 * paginação, rate limit e circuit breaker — a mesma que o B3 apontou como
 * sintoma de padrão de acesso equivocado. O que se perde: frescor intradiário,
 * que a resposta 6 dispensou explicitamente.
 *
 * <p>REST volta a ser necessário se a plataforma precisar ver um voto no mesmo
 * dia. É o gatilho para reabrir esta decisão.
 */
@Component
public class JobIncremental {

    private static final Logger log = LoggerFactory.getLogger(JobIncremental.class);

    private final BaixadorDeArquivos baixador;
    private final JobDeBackfillCamara backfill;
    private final DerivadorDeAusencia derivador;
    private final FinalizadorDeIngestao finalizador;

    JobIncremental(BaixadorDeArquivos baixador, JobDeBackfillCamara backfill,
                   DerivadorDeAusencia derivador, FinalizadorDeIngestao finalizador) {
        this.baixador = baixador;
        this.backfill = backfill;
        this.derivador = derivador;
        this.finalizador = finalizador;
    }

    /**
     * @param base endereços a consultar, por nome de arquivo local
     * @return o que mudou, e até quando a fonte está lida
     */
    public Resultado executar(Execucao execucao, int ano, Path trabalho,
                              EnderecosDoAno base) {
        Instant desde = execucao.watermarkAnterior();

        // Proposições, temas e autoria entram juntos na carga; se um mudou, os
        // três precisam estar em disco. Por isso a checagem é do GRUPO, e não
        // arquivo a arquivo.
        var materias = baixarGrupo(trabalho, desde, Map.of(
            "proposicoes.csv", base.proposicoes(),
            "temas.csv", base.temas(),
            "autores.csv", base.autores()));

        var votacoes = baixarGrupo(trabalho, desde, Map.of(
            "votacoes.csv", base.votacoes(),
            "votos.csv", base.votos()));

        if (materias.vazio() && votacoes.vazio()) {
            log.info("nada mudou na Camara desde {}: encerrando sem trabalho", desde);
            // O watermark fica onde estava: nada foi processado, e mover o
            // marcador sobre uma janela vazia é como uma execução falha que
            // finge sucesso.
            return Resultado.semMudanca(desde);
        }

        int quantasMaterias = 0, quantasVotacoes = 0, quantosVotos = 0;

        if (!materias.vazio()) {
            var r = backfill.carregarProposicoes(execucao,
                materias.arquivos().get("proposicoes.csv"),
                materias.arquivos().get("temas.csv"),
                materias.arquivos().get("autores.csv"));
            quantasMaterias = r.materias();
        }

        if (!votacoes.vazio()) {
            // Votação antes de voto: o voto referencia a votação por FK.
            quantasVotacoes = backfill.carregarVotacoes(execucao,
                votacoes.arquivos().get("votacoes.csv"));
            quantosVotos = backfill.carregarVotos(execucao,
                votacoes.arquivos().get("votos.csv")).gravados();

            // Voto novo muda quem faltou: a derivação precisa acompanhar, senão
            // a ausência publicada fica de ontem.
            derivador.derivar(execucao, CasaLegislativa.CAMARA);
        }

        finalizador.finalizar(execucao);

        Instant watermark = maiorEntre(materias, votacoes, desde);
        return new Resultado(true, watermark, quantasVotacoes, quantosVotos,
                             quantasMaterias);
    }

    /**
     * Baixa o grupo inteiro se <b>qualquer</b> membro tiver mudado.
     *
     * <p>Meio grupo em disco seria pior que nenhum: a carga de proposições
     * cruza os três arquivos, e um deles velho produziria autoria apontando
     * para matéria que não existe mais.
     */
    private Grupo baixarGrupo(Path trabalho, Instant desde, Map<String, URI> grupo) {
        var mudaram = new LinkedHashMap<String, BaixadorDeArquivos.ArquivoBaixado>();
        for (var entrada : grupo.entrySet()) {
            baixador.baixarSeMudou(entrada.getValue(),
                                   trabalho.resolve(entrada.getKey()), desde)
                .ifPresent(a -> mudaram.put(entrada.getKey(), a));
        }
        if (mudaram.isEmpty()) return Grupo.semMudanca();

        var completo = new LinkedHashMap<String, Path>();
        for (var entrada : grupo.entrySet()) {
            var jaBaixado = mudaram.get(entrada.getKey());
            completo.put(entrada.getKey(), jaBaixado != null
                ? jaBaixado.caminho()
                // Não mudou, mas o grupo mudou: baixa incondicionalmente.
                : baixador.baixarSeMudou(entrada.getValue(),
                                         trabalho.resolve(entrada.getKey()), null)
                    .orElseThrow(() -> new IllegalStateException(
                        "fonte recusou entregar " + entrada.getValue()))
                    .caminho());
        }

        Instant maisRecente = mudaram.values().stream()
            .map(BaixadorDeArquivos.ArquivoBaixado::modificadoEm)
            .max(Instant::compareTo).orElseThrow();
        return new Grupo(completo, maisRecente);
    }

    /** Arquivos de um grupo em disco, e até quando a fonte foi lida. */
    private record Grupo(Map<String, Path> arquivos, Instant maisRecente) {

        static Grupo semMudanca() {
            return new Grupo(Map.of(), null);
        }

        boolean vazio() {
            return arquivos.isEmpty();
        }
    }

    /**
     * O watermark é o maior {@code Last-Modified} <b>efetivamente processado</b>.
     *
     * <p>Nunca retrocede, e nunca avança além do que foi lido: um marcador à
     * frente do dado faz o ciclo seguinte pular a janela em silêncio, que é o
     * modo de falha do B6.
     */
    private static Instant maiorEntre(Grupo a, Grupo b, Instant desde) {
        Instant maior = desde;
        for (Instant candidato : new Instant[]{a.maisRecente(), b.maisRecente()}) {
            if (candidato != null && (maior == null || candidato.isAfter(maior))) {
                maior = candidato;
            }
        }
        return maior;
    }

    /** Endereços dos cinco arquivos de um ano. */
    public record EnderecosDoAno(URI proposicoes, URI temas, URI autores,
                                 URI votacoes, URI votos) {

        public static EnderecosDoAno daCamara(int ano) {
            return new EnderecosDoAno(
                ArquivosDaCamara.proposicoes(ano), ArquivosDaCamara.temas(ano),
                ArquivosDaCamara.autores(ano), ArquivosDaCamara.votacoes(ano),
                ArquivosDaCamara.votos(ano));
        }

        public List<URI> todos() {
            return List.of(proposicoes, temas, autores, votacoes, votos);
        }
    }

    public record Resultado(boolean houveMudanca, Instant watermarkNovo,
                            int votacoes, int votos, int materias) {

        static Resultado semMudanca(Instant watermark) {
            return new Resultado(false, watermark, 0, 0, 0);
        }
    }
}
