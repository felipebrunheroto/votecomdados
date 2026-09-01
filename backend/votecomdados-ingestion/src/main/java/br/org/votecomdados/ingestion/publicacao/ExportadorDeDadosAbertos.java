package br.org.votecomdados.ingestion.publicacao;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Publica de volta o banco curado, como dado aberto.
 *
 * <h2>Por que existe</h2>
 *
 * A plataforma faz uma afirmação que ninguém consegue conferir de fora: "este
 * deputado é esta candidata". TSE e Casas não compartilham identificador comum,
 * então todo vínculo é interpretação nossa — e uma plataforma de transparência
 * que não pode ser auditada está pedindo fé, não mostrando dado. Publicar o
 * curado é o que permite alguém refazer o cruzamento e apontar nosso erro.
 *
 * <h2>O recorte publicável é o schema {@code dados_abertos}, não esta classe</h2>
 *
 * As colunas saem de views, e é isso que torna a exclusão de dado pessoal
 * <b>verificável</b> por invariante (T48) em vez de prometida em código. Esta
 * classe só transporta: se ela tentasse escolher colunas, viraria um segundo
 * lugar onde o recorte pode divergir.
 */
@Service
public class ExportadorDeDadosAbertos {

    private static final Logger log =
        LoggerFactory.getLogger(ExportadorDeDadosAbertos.class);

    /** Ordem estável para o diretório ficar legível. */
    private static final List<String> VIEWS = List.of(
        "politico", "candidatura", "identificador_externo",
        "proposicao", "proposicao_tema", "proposicao_autor",
        "votacao", "voto_nominal", "mandato_exercicio",
        "mapeamento_voto", "mapeamento_situacao", "cobertura_fonte");

    private final DataSource dataSource;

    ExportadorDeDadosAbertos(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Gera um instantâneo datado.
     *
     * <p>O diretório <b>nunca é sobrescrito</b>: uma análise publicada precisa
     * continuar verificável, e um arquivo que muda embaixo de quem o citou não
     * serve de evidência. {@code latest} é conveniência, não endereço de
     * citação.
     *
     * @return diretório gerado
     * @throws IllegalStateException se o instantâneo do dia já existe
     */
    public Path exportar(Path base) {
        LocalDate hoje = LocalDate.now();
        Path destino = base.resolve(hoje.toString());
        if (Files.exists(destino)) {
            throw new IllegalStateException(
                destino + " ja existe; instantaneo datado nao e sobrescrito");
        }

        try (Connection c = dataSource.getConnection()) {
            Files.createDirectories(destino);
            CopyManager copy = c.unwrap(PGConnection.class).getCopyAPI();

            var linhasPorArquivo = new LinkedHashMap<String, Long>();
            for (String view : VIEWS) {
                Path arquivo = destino.resolve(view + ".csv");
                try (Writer saida = Files.newBufferedWriter(arquivo, StandardCharsets.UTF_8)) {
                    long linhas = copy.copyOut(
                        "COPY (SELECT * FROM dados_abertos." + view
                        + ") TO STDOUT WITH (FORMAT csv, HEADER true)", saida);
                    linhasPorArquivo.put(view, linhas);
                }
            }

            var manifesto = manifesto(c);
            Files.writeString(destino.resolve("manifesto.json"), manifesto,
                              StandardCharsets.UTF_8);
            Files.writeString(destino.resolve("LEIA-ME.md"),
                              metodologia(hoje, c), StandardCharsets.UTF_8);

            log.info("dados abertos publicados em {}: {}", destino, linhasPorArquivo);
            return destino;
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("falha ao exportar dados abertos", e);
        }
    }

    private static String manifesto(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT jsonb_pretty(to_jsonb(m)) FROM dados_abertos.manifesto m")) {
            return rs.next() ? rs.getString(1) : "{}";
        }
    }

    /**
     * A metodologia sai com os números do instantâneo, inclusive os
     * desconfortáveis.
     *
     * <p>Publicar quantos vínculos vieram de similaridade — e quantos desses
     * ainda não passaram por revisão humana — é o que separa um pacote de dados
     * abertos de uma peça de marketing. Omitir esse número tornaria o resto do
     * arquivo menos confiável, não mais.
     */
    private static String metodologia(LocalDate data, Connection c) throws SQLException {
        Map<String, Long> n = new LinkedHashMap<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("""
                SELECT votos_derivados_por_nos, vinculos_por_similaridade,
                       vinculos_fuzzy_sem_revisao_humana
                  FROM dados_abertos.manifesto
                """)) {
            if (rs.next()) {
                n.put("derivados", rs.getLong(1));
                n.put("fuzzy", rs.getLong(2));
                n.put("fuzzySemRevisao", rs.getLong(3));
            }
        }

        return """
            # VoteComDados — dados abertos (%s)

            Instantâneo do banco curado da plataforma. Este diretório **não muda**: se
            você citou este pacote, ele continuará como está.

            Licença: **CC BY 4.0**. O dado de origem é público e pertence aos órgãos que o
            publicam (TSE, Câmara dos Deputados, Senado Federal, Alesp); o que licenciamos
            aqui é a consolidação e o cruzamento.

            ## O que exige leitura antes do uso

            **1. O cruzamento é afirmação nossa, não das fontes.** TSE e Casas não
            compartilham identificador comum. `identificador_externo.csv` traz cada vínculo
            com o método (`DETERMINISTICO` ou `FUZZY`), o score e se passou por revisão
            humana. Neste instantâneo — vínculos por similaridade: **%d**, destes ainda sem
            revisão humana: **%d**. Comece por eles se quiser conferir nosso trabalho.

            **2. Ausência e licença, na Câmara, são cálculo nosso.** A Casa publica apenas
            quem registrou voto. As linhas com `origem_registro = DERIVADO` (**%d** aqui)
            vêm do cruzamento entre a votação e quem estava em exercício na data. Trate-as
            como interpretação, não como registro oficial — e `voto_origem` vem vazio
            justamente nelas. No Senado é o contrário: a Casa publica a bancada inteira, e
            lá nada é derivado.

            **3. A cobertura é assimétrica, e isso distorce comparações.**
            `cobertura_fonte.csv` diz até onde cada fonte vai, **por Casa**: voto nominal
            da Câmara existe desde 2001, o do Senado desde 1991; a Alesp publica voto de
            comissão, não de plenário; câmaras municipais não publicam nada estruturado.
            **Comparar dois candidatos sem ler esse arquivo é comparar a transparência das
            Casas, não a atuação das pessoas.**

            **4. A base é a coorte de 2026.** Só existe registro de quem é candidato na
            eleição de 2026 — não é uma base histórica completa de parlamentares.

            **5. A tradução dos rótulos é editorial.** `mapeamento_voto.csv` e
            `mapeamento_situacao.csv` mostram o que decidimos que cada rótulo da fonte
            significa. Discordar dessa tradução é discordar de nós, não da fonte — e o
            `voto_origem` preservado permite refazê-la.

            ## O que não está aqui

            CPF (nem em hash), identificação de quem fez a curadoria, payload bruto,
            quarentena e históricos internos de alteração. Nada disso serve à auditoria do
            cruzamento, e publicá-lo só ampliaria a superfície de dado pessoal.

            ## Erro encontrado?

            Abra uma issue no repositório com o `politico_id` e o arquivo. Um vínculo
            errado é o pior defeito possível nesta plataforma — reportá-lo é a contribuição
            mais útil que existe.
            """.formatted(data, n.getOrDefault("fuzzy", 0L),
                          n.getOrDefault("fuzzySemRevisao", 0L),
                          n.getOrDefault("derivados", 0L));
    }
}
