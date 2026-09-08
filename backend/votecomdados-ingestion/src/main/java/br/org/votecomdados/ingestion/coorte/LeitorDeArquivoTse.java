package br.org.votecomdados.ingestion.coorte;

import br.org.votecomdados.ingestion.csv.LeitorDeCsv;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Abre o {@code consulta_cand_AAAA.zip} do TSE e entrega uma linha por vez.
 *
 * <h2>Três coisas verificadas no arquivo real (31/08/2026)</h2>
 *
 * <ol>
 *   <li><b>A codificação é latin-1, não UTF-8.</b> Ler como UTF-8 estoura em
 *       {@code Ç} — e, com decodificação tolerante, corromperia silenciosamente
 *       todo nome acentuado do país.</li>
 *   <li><b>{@code _BRASIL.csv} é a união exata dos arquivos por UF</b> —
 *       conferido: 20.809 candidaturas dos dois lados, nenhuma exclusiva.
 *       Processar os dois dobraria o trabalho sem acrescentar uma linha.</li>
 *   <li><b>O zip traz um {@code leiame.pdf}</b> junto dos CSVs. Iterar entradas
 *       sem filtrar extensão faria o parser tentar ler um PDF como CSV.</li>
 * </ol>
 */
@Component
public class LeitorDeArquivoTse {

    private static final Logger log = LoggerFactory.getLogger(LeitorDeArquivoTse.class);

    /** Verificado no arquivo: acentuação em ISO-8859-1. */
    private static final Charset CODIFICACAO = Charset.forName("ISO-8859-1");

    private final LeitorDeCsv csv;

    LeitorDeArquivoTse(LeitorDeCsv csv) {
        this.csv = csv;
    }

    /**
     * Lê o zip inteiro, pulando o consolidado nacional e o leia-me.
     *
     * <p>Devolve lista, não fluxo preguiçoso: são ~21 mil linhas por eleição
     * geral, e a simplicidade de fechar o zip logo vale mais do que a memória
     * economizada. Se um dia a coorte incluir eleição municipal — onde o número
     * passa de 400 mil — vale reconsiderar.
     */
    public List<JsonNode> ler(Path zip) {
        var todas = new ArrayList<JsonNode>();
        porArquivoDeUf(zip, todas::addAll);
        return todas;
    }

    /**
     * Entrega um arquivo de UF por vez, em vez do zip inteiro de uma só.
     *
     * <p>É o que torna a eleição municipal viável. Uma eleição geral são ~21
     * mil candidaturas; uma municipal passa de 400 mil — e materializar isso
     * como {@code JsonNode} não cabe no heap da task de ingestão. Por UF, o
     * pico é o maior estado, não o país.
     *
     * <p>Chamar {@code carregarAno} uma vez por arquivo é seguro: a coorte já
     * era idempotente por {@code (sq_candidato, ano)}, e o expurgo do
     * {@code cpf_hmac} acontece só no {@code encerrar}.
     */
    public void porArquivoDeUf(Path zip, Consumer<List<JsonNode>> consumidor) {
        try (ZipFile arquivo = new ZipFile(zip.toFile())) {
            var entradas = entradasDeUf(arquivo);

            int total = 0;
            for (ZipEntry entrada : entradas) {
                var linhas = new ArrayList<JsonNode>();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(
                        arquivo.getInputStream(entrada), CODIFICACAO))) {
                    csv.ler(r, linhas);
                }
                log.debug("{}: {} candidatura(s)", entrada.getName(), linhas.size());
                total += linhas.size();
                consumidor.accept(linhas);
            }
            log.info("TSE {}: {} candidatura(s) em {} arquivo(s) por UF",
                     zip.getFileName(), total, entradas.size());
        } catch (IOException e) {
            throw new IllegalStateException("falha ao ler " + zip, e);
        }
    }

    /**
     * O ano da eleição, lido só da primeira linha do primeiro arquivo.
     *
     * <p>Serve para ordenar os pacotes sem abrir nenhum por inteiro — abrir
     * sete pacotes para decidir a ordem seria justamente o que não cabe na
     * memória.
     */
    public int anoDaEleicao(Path zip) {
        try (ZipFile arquivo = new ZipFile(zip.toFile())) {
            var entradas = entradasDeUf(arquivo);
            if (entradas.isEmpty()) return 0;

            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    arquivo.getInputStream(entradas.getFirst()), CODIFICACAO))) {
                String cabecalho = r.readLine();
                String primeira = r.readLine();
                if (cabecalho == null || primeira == null) return 0;

                var colunas = LeitorDeCsv.separar(cabecalho.replace("\ufeff", ""));
                int coluna = colunas.indexOf("ANO_ELEICAO");
                if (coluna < 0) return 0;

                var valores = LeitorDeCsv.separar(primeira);
                if (coluna >= valores.size()) return 0;
                return Integer.parseInt(valores.get(coluna).trim());
            }
        } catch (IOException | NumberFormatException e) {
            // Sem ano legivel, o pacote perde a disputa pela primeira posicao.
            // Nao e motivo para abortar: quem decide se ele serve e o job.
            log.warn("nao consegui ler o ano de {}", zip.getFileName(), e);
            return 0;
        }
    }

    private static List<ZipEntry> entradasDeUf(ZipFile arquivo) {
        return arquivo.stream()
            .filter(e -> !e.isDirectory())
            .filter(e -> e.getName().toLowerCase().endsWith(".csv"))
            // O consolidado nacional é a união exata das UFs: incluí-lo
            // processaria tudo duas vezes.
            .filter(e -> !e.getName().toUpperCase().endsWith("_BRASIL.CSV"))
            .map(ZipEntry.class::cast)
            .toList();
    }

    /**
     * Lê um CSV solto, fora do zip.
     *
     * <p>Existe para dois usos legítimos: reprocessar uma UF isolada sem
     * rebaixar o pacote inteiro, e testar contra a amostra fixada em
     * {@code db/golden/} — que é CSV justamente para poder ser lida em diff.
     *
     * <p>A codificação é a mesma do zip: quem exporta uma UF do pacote leva o
     * latin-1 junto.
     */
    public List<JsonNode> lerCsv(Path arquivo) {
        return csv.ler(arquivo);
    }

    /** Conveniência para quem consome em laço. */
    public Iterator<JsonNode> iterar(Path zip) {
        return ler(zip).iterator();
    }
}
