package br.org.votecomdados.ingestion;

import br.org.votecomdados.core.dominio.Enums.CasaLegislativa;
import br.org.votecomdados.core.dominio.Enums.Fonte;
import br.org.votecomdados.core.dominio.Enums.TipoJob;
import br.org.votecomdados.ingestion.alesp.OrquestradorDaAlesp;
import br.org.votecomdados.ingestion.armazenamento.ArmazenamentoDeObjetos;
import br.org.votecomdados.ingestion.coorte.JobDeCoorte;
import br.org.votecomdados.ingestion.coorte.LeitorDeArquivoTse;
import br.org.votecomdados.ingestion.download.JobIncremental;
import br.org.votecomdados.ingestion.execucao.ControleDeExecucaoService;
import br.org.votecomdados.ingestion.execucao.Execucao;
import br.org.votecomdados.ingestion.execucao.ExecucaoConcorrenteException;
import br.org.votecomdados.ingestion.massa.JobDeBackfill;
import br.org.votecomdados.ingestion.publicacao.ExportadorDeDadosAbertos;
import br.org.votecomdados.ingestion.senado.OrquestradorDoSenado;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Lê {@code --job}, {@code --fonte} e {@code --ano} e executa.
 *
 * <p>Esta classe fixa o <b>contrato de saída</b> do processo, e é ele que o
 * scheduler enxerga: um worker que termina em zero depois de quebrar faz a
 * falha virar silêncio, que é o modo de erro que este projeto mais combate.
 */
@Component
// Desligável para que o contexto possa subir sem disparar um job — é o que os
// testes de integração precisam. Em produção fica ligado por omissão: um
// worker que sobe e não faz nada seria uma falha silenciosa.
@ConditionalOnProperty(name = "votecomdados.worker.habilitado",
                       havingValue = "true", matchIfMissing = true)
public class SeletorDeJob implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(SeletorDeJob.class);

    /** Já havia execução viva: encerrar sem enfileirar não é sucesso nem falha. */
    static final int SAIDA_JA_EM_ANDAMENTO = 2;
    static final int SAIDA_FALHA = 1;

    private final ControleDeExecucaoService controle;
    private final JobDeCoorte coorte;
    private final LeitorDeArquivoTse leitorTse;
    private final JobIncremental incremental;
    private final OrquestradorDaAlesp alesp;
    private final OrquestradorDoSenado senado;
    private final JobDeBackfill backfillCamara;
    private final ExportadorDeDadosAbertos exportador;
    private final ArmazenamentoDeObjetos armazenamento;
    private int codigoDeSaida = 0;

    SeletorDeJob(ControleDeExecucaoService controle, JobDeCoorte coorte,
                 LeitorDeArquivoTse leitorTse, JobIncremental incremental,
                 OrquestradorDaAlesp alesp, OrquestradorDoSenado senado,
                 JobDeBackfill backfillCamara, ExportadorDeDadosAbertos exportador,
                 ArmazenamentoDeObjetos armazenamento) {
        this.controle = controle;
        this.coorte = coorte;
        this.leitorTse = leitorTse;
        this.incremental = incremental;
        this.alesp = alesp;
        this.senado = senado;
        this.backfillCamara = backfillCamara;
        this.exportador = exportador;
        this.armazenamento = armazenamento;
    }

    @Override
    public void run(ApplicationArguments args) {
        TipoJob job;
        Fonte fonte;
        try {
            job = valorObrigatorio(args, "job", TipoJob.class);
            fonte = valorObrigatorio(args, "fonte", Fonte.class);
        } catch (IllegalArgumentException e) {
            log.error("argumentos invalidos: {}", e.getMessage());
            codigoDeSaida = SAIDA_FALHA;
            return;
        }

        Execucao execucao = null;
        try {
            execucao = controle.iniciar(fonte, job, parametros(args));
            executar(execucao, job, fonte, args);
        } catch (ExecucaoConcorrenteException e) {
            log.warn(e.getMessage());
            codigoDeSaida = SAIDA_JA_EM_ANDAMENTO;
        } catch (RuntimeException e) {
            if (execucao != null) {
                controle.falhar(execucao, e);
            } else {
                log.error("falha antes de abrir a execucao", e);
            }
            codigoDeSaida = SAIDA_FALHA;
        }
    }

    private void executar(Execucao execucao, TipoJob job, Fonte fonte,
                          ApplicationArguments args) {
        switch (job) {
            case COORTE -> {
                if (fonte != Fonte.TSE) {
                    throw new IllegalArgumentException(
                        "a coorte vem do TSE; --fonte=" + fonte + " nao faz sentido");
                }
                Path zip = arquivoObrigatorio(args, "arquivo");
                var linhas = zip.toString().toLowerCase(Locale.ROOT).endsWith(".zip")
                    ? leitorTse.ler(zip) : leitorTse.lerCsv(zip);
                var r = coorte.carregarAno(execucao, linhas.iterator());
                coorte.encerrar();
                // O watermark da coorte é o instante da coleta: o TSE não
                // publica Last-Modified utilizável no pacote.
                controle.concluir(execucao, Instant.now(), r.processados(), r.rejeitados());
            }
            case INCREMENTAL -> {
                if (fonte == Fonte.ALESP) {
                    // A Alesp publica a série inteira num arquivo só, regerada
                    // diariamente: não há recorte por ano a passar adiante, e o
                    // incremental é o If-Modified-Since dos seis arquivos.
                    var r = alesp.executar(execucao, diretorioDeTrabalho(),
                                           controle.watermarkAtual(fonte).orElse(null));
                    if (r.houveMudanca()) {
                        publicarDadosAbertos(args);
                    }
                    controle.concluir(execucao, r.watermarkNovo(),
                                      r.proposituras() + r.votacoes() + r.votos(), 0);
                    return;
                }
                if (fonte == Fonte.SENADO) {
                    // A API do Senado nao publica Last-Modified nem ETag; o
                    // watermark vem da maior dataSessao vista, nao do HTTP.
                    int anoSenado = inteiro(args, "ano", LocalDate.now().getYear());
                    var r = senado.executar(execucao, anoSenado,
                                            controle.watermarkAtual(fonte).orElse(null));
                    if (r.houveMudanca()) {
                        publicarDadosAbertos(args);
                    }
                    controle.concluir(execucao, r.watermarkNovo(),
                                      r.votacoes() + r.votos(), 0);
                    return;
                }
                if (fonte != Fonte.CAMARA) {
                    throw new IllegalArgumentException(
                        "o incremental so esta implementado para CAMARA, ALESP e SENADO; "
                        + "--fonte=" + fonte + " nao e reconhecida");
                }
                int ano = inteiro(args, "ano", LocalDate.now().getYear());
                Path trabalho = diretorioDeTrabalho();
                var r = incremental.executar(execucao, ano, trabalho,
                    JobIncremental.EnderecosDoAno.daCamara(ano));

                if (r.houveMudanca()) {
                    publicarDadosAbertos(args);
                }
                controle.concluir(execucao, r.watermarkNovo(),
                                  r.votacoes() + r.votos() + r.materias(), 0);
            }
            case BACKFILL -> {
                if (fonte != Fonte.CAMARA) {
                    throw new IllegalArgumentException(
                        "o backfill por serie de anos so esta implementado para CAMARA; "
                        + "a Alesp publica a serie inteira num arquivo so (INCREMENTAL "
                        + "ja carrega tudo) e o Senado reprocessa o ano inteiro a cada "
                        + "ciclo (idem) -- nenhum dos dois precisa de backfill por ano");
                }
                // --desde: 2001 e o primeiro ano com voto nominal publicado. Um
                // backfill de 25 anos que morre no meio nao recomeca do zero --
                // o operador retoma passando o ano onde parou.
                int desde = inteiro(args, "desde",
                                    JobDeBackfill.PRIMEIRO_ANO_COM_VOTO_NOMINAL);
                int ate = inteiro(args, "ate", LocalDate.now().getYear());
                var r = backfillCamara.executar(execucao, desde, ate, diretorioDeTrabalho());

                publicarDadosAbertos(args);
                controle.concluir(execucao, r.watermarkNovo(),
                                  r.materias() + r.votacoes() + r.votos(), 0);
            }
        }
    }

    /**
     * Publicar é parte de terminar a ingestão — mas falhar aqui não pode
     * desfazer o dado já gravado. O pacote sai no ciclo seguinte.
     */
    private void publicarDadosAbertos(ApplicationArguments args) {
        var destino = args.getOptionValues("dados-abertos");
        if (destino == null || destino.isEmpty()) return;
        try {
            String alvo = destino.getFirst();
            if (ArmazenamentoDeObjetos.ehRemoto(alvo)) {
                publicarNoObjectStorage(alvo);
            } else {
                exportador.exportar(Path.of(alvo));
            }
        } catch (RuntimeException e) {
            log.warn("ingestao concluida, mas a publicacao dos dados abertos falhou; "
                     + "sera refeita no proximo ciclo", e);
        }
    }

    /**
     * Gera o instantâneo num diretório temporário e o envia ao object storage.
     *
     * <p>A checagem de existência <b>antes</b> de exportar não é redundante com
     * a que o exportador já faz: aquela olha o disco, e o disco aqui é sempre
     * um temporário novo — logo sempre "livre". Sem esta, o upload
     * sobrescreveria em silêncio um pacote já publicado, que é exatamente o
     * que a regra do instantâneo datado existe para impedir (ARQUITETURA.md
     * § 8b: dado citável que muda embaixo de quem citou não é evidência).
     */
    private void publicarNoObjectStorage(String uriBase) {
        String base = uriBase.endsWith("/") ? uriBase.substring(0, uriBase.length() - 1) : uriBase;
        String uriDoDia = base + "/" + LocalDate.now();

        if (armazenamento.existeAlgoSob(uriDoDia)) {
            throw new IllegalStateException(
                uriDoDia + " ja existe; instantaneo datado nao e sobrescrito");
        }

        Path gerado = exportador.exportar(diretorioDeTrabalho());
        armazenamento.enviarDiretorio(gerado, uriDoDia);
    }

    @Override
    public int getExitCode() {
        return codigoDeSaida;
    }

    /**
     * Parâmetros do job, gravados em {@code ingestao_execucao.parametros}.
     *
     * <p>Guardar "com que argumentos isto rodou" ao lado do resultado é o que
     * permite reproduzir uma execução meses depois — sem isso, um backfill que
     * saiu errado não tem como ser refeito igual para comparação.
     */
    private static String parametros(ApplicationArguments args) {
        var ano = args.getOptionValues("ano");
        if (ano == null || ano.isEmpty()) return "{}";
        try {
            return "{\"ano\": " + Integer.parseInt(ano.getFirst()) + "}";
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--ano=" + ano.getFirst() + " nao e um ano");
        }
    }

    private static Path diretorioDeTrabalho() {
        try {
            return Files.createTempDirectory("votecomdados-ingestao");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("sem diretorio de trabalho", e);
        }
    }

    /**
     * Resolve {@code --arquivo} para um caminho legível no disco, baixando do
     * object storage antes se vier como {@code s3://bucket/chave}.
     *
     * <p>O pacote do TSE é baixado à mão pelo owner (o TSE bloqueia parte das
     * máquinas — ver PLANO_IMPLEMENTACAO.md, W3) e colocado no bucket; sem
     * este desvio não havia como entregá-lo a um container Fargate, e a
     * coorte simplesmente não rodava em produção.
     */
    private Path arquivoObrigatorio(ApplicationArguments args, String nome) {
        var valores = args.getOptionValues(nome);
        if (valores == null || valores.isEmpty()) {
            throw new IllegalArgumentException("--" + nome + " e obrigatorio neste job");
        }
        String valor = valores.getFirst();

        if (ArmazenamentoDeObjetos.ehRemoto(valor)) {
            return armazenamento.baixar(valor, diretorioDeTrabalho());
        }

        Path caminho = Path.of(valor);
        if (!Files.isReadable(caminho)) {
            throw new IllegalArgumentException("nao consigo ler " + caminho);
        }
        return caminho;
    }

    private static int inteiro(ApplicationArguments args, String nome, int padrao) {
        var valores = args.getOptionValues(nome);
        if (valores == null || valores.isEmpty()) return padrao;
        try {
            return Integer.parseInt(valores.getFirst());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + nome + " precisa ser numero");
        }
    }

    private static <E extends Enum<E>> E valorObrigatorio(
            ApplicationArguments args, String nome, Class<E> tipo) {
        var valores = args.getOptionValues(nome);
        if (valores == null || valores.isEmpty()) {
            throw new IllegalArgumentException("--" + nome + " e obrigatorio");
        }
        String bruto = valores.getFirst().toUpperCase(Locale.ROOT);
        try {
            return Enum.valueOf(tipo, bruto);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "--" + nome + "=" + bruto + " nao e valido; aceitos: "
                + java.util.Arrays.toString(tipo.getEnumConstants()));
        }
    }
}
