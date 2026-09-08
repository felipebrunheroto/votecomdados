package br.org.votecomdados.ingestion.coorte;

import static org.assertj.core.api.Assertions.assertThat;

import br.org.votecomdados.ingestion.csv.LeitorDeCsv;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * O pacote do TSE é lido por arquivo de UF, não de uma vez só.
 *
 * <p>Uma eleição geral são ~21 mil candidaturas; uma municipal passa de 400
 * mil, e materializar isso como {@code JsonNode} não cabe no heap de 768 MB da
 * task de ingestão. O que estes casos garantem é que o leitor entrega em
 * pedaços e sabe dizer o ano sem abrir o pacote inteiro.
 */
class LeitorDeArquivoTseTest {

    // Sem Spring: ler zip nao depende de banco nem de configuracao.
    private final LeitorDeArquivoTse leitor =
        new LeitorDeArquivoTse(new LeitorDeCsv(new ObjectMapper()));

    private static final Path AMOSTRA =
        Path.of("..", "..", "db", "golden", "tse-consulta-cand-2026-amostra.csv");

    /** Monta um zip no formato do TSE: um CSV por UF, mais o consolidado. */
    private Path zipCom(Path destino, List<String> ufs, boolean comBrasil) throws IOException {
        var linhas = Files.readAllLines(AMOSTRA, StandardCharsets.ISO_8859_1);
        Path zip = destino.resolve("consulta_cand_2026.zip");
        try (var saida = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (String uf : ufs) {
                escrever(saida, "consulta_cand_2026_" + uf + ".csv", linhas);
            }
            if (comBrasil) {
                escrever(saida, "consulta_cand_2026_BRASIL.csv", linhas);
            }
            // O leia-me existe no pacote real e não pode virar CSV.
            escrever(saida, "leiame.pdf", List.of("%PDF-1.4"));
        }
        return zip;
    }

    private static void escrever(ZipOutputStream saida, String nome, List<String> linhas)
            throws IOException {
        saida.putNextEntry(new ZipEntry(nome));
        OutputStream fluxo = saida;
        fluxo.write(String.join("\n", linhas).getBytes(StandardCharsets.ISO_8859_1));
        saida.closeEntry();
    }

    @Test
    void entrega_um_arquivo_de_uf_por_vez(@TempDir Path dir) throws IOException {
        Path zip = zipCom(dir, List.of("SP", "RJ", "MG"), true);

        var lotes = new ArrayList<Integer>();
        leitor.porArquivoDeUf(zip, linhas -> lotes.add(linhas.size()));

        assertThat(lotes)
            .as("tres UFs viram tres lotes; BRASIL e o PDF ficam de fora")
            .hasSize(3);
        assertThat(lotes).allSatisfy(n -> assertThat(n).isPositive());
    }

    /** O consolidado nacional é a união das UFs: incluí-lo dobraria tudo. */
    @Test
    void o_consolidado_nacional_e_o_leiame_ficam_de_fora(@TempDir Path dir) throws IOException {
        Path comBrasil = zipCom(dir, List.of("SP"), true);
        Path semBrasil = zipCom(Files.createDirectory(dir.resolve("sem")), List.of("SP"), false);

        assertThat(leitor.ler(comBrasil)).hasSameSizeAs(leitor.ler(semBrasil));
    }

    @Test
    void diz_o_ano_sem_abrir_o_pacote_inteiro(@TempDir Path dir) throws IOException {
        assertThat(leitor.anoDaEleicao(zipCom(dir, List.of("SP"), true))).isEqualTo(2026);
    }

    @Test
    void pacote_ilegivel_devolve_ano_zero_em_vez_de_estourar(@TempDir Path dir)
            throws IOException {
        Path quebrado = dir.resolve("nao-e-zip.zip");
        Files.writeString(quebrado, "isto nao e um zip");

        assertThat(leitor.anoDaEleicao(quebrado))
            .as("perde a disputa pela primeira posicao, mas nao aborta o job")
            .isZero();
    }

    /** O que o `ler` devolve tem de ser a soma dos lotes — mesma leitura. */
    @Test
    void ler_inteiro_e_a_soma_dos_lotes(@TempDir Path dir) throws IOException {
        Path zip = zipCom(dir, List.of("SP", "RJ"), true);

        List<JsonNode> tudo = leitor.ler(zip);
        var soma = new ArrayList<JsonNode>();
        leitor.porArquivoDeUf(zip, soma::addAll);

        assertThat(tudo).hasSameSizeAs(soma);
    }
}
