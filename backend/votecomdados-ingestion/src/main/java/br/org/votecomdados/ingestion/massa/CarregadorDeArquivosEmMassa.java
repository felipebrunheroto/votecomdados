package br.org.votecomdados.ingestion.massa;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Carrega um CSV inteiro com {@code COPY} e transforma dentro do banco.
 *
 * <h2>Por que ELT e não ETL</h2>
 *
 * É a decisão R1, e ela nasceu de um defeito: o desenho original paginava a API
 * REST e fazia uma chamada de detalhe por proposição — centenas de milhares de
 * requisições contra uma API pública, de onde vinha a estimativa de "~200h de
 * worker". A Câmara publica os mesmos dados como arquivos anuais. O backfill
 * virou dezenas de downloads, e a carga passou a ser um {@code COPY}.
 *
 * <h2>A tabela temporária é a allowlist</h2>
 *
 * O CSV entra numa tabela <b>temporária de sessão</b>, e só as colunas
 * declaradas na transformação seguem para o schema curado. A tabela morre com a
 * conexão: nada do arquivo bruto sobrevive ao job. Para os arquivos da Câmara
 * isso é disciplina, não necessidade de LGPD — eles não trazem CPF —, mas é a
 * mesma regra do staging, e vale mantê-la uniforme.
 *
 * <p>Tudo acontece numa conexão só, com transação explícita: {@code COPY},
 * transformação e {@code SET LOCAL} da execução precisam enxergar a mesma
 * tabela temporária e falhar juntos.
 */
@Component
public class CarregadorDeArquivosEmMassa {

    private static final Logger log =
        LoggerFactory.getLogger(CarregadorDeArquivosEmMassa.class);

    /** Temporária de sessão: nome fixo não colide entre execuções. */
    private static final String TABELA = "carga";

    private final DataSource dataSource;

    CarregadorDeArquivosEmMassa(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * @param arquivo  CSV com cabeçalho, delimitado por {@code ;}
     * @param execucaoId identifica a execução aos gatilhos de histórico
     * @param transformacao roda com a tabela {@value #TABELA} já carregada
     */
    public <T> T carregarETransformar(Path arquivo, long execucaoId,
                                      Transformacao<T> transformacao) {
        return carregarVarios(java.util.Map.of(TABELA, arquivo), execucaoId,
                              (c, tabelas) -> transformacao.aplicar(c, TABELA));
    }

    /**
     * Carrega vários arquivos que precisam ser vistos <b>juntos</b>.
     *
     * <p>Existe porque proposições, temas e autoria não são independentes: só
     * se sabe quais proposições interessam depois de saber quem as assinou, e
     * o tema chega num terceiro arquivo. Carregar um de cada vez exigiria
     * tabela intermediária persistente — mais peças, e uma janela em que o
     * banco tem meio carregamento.
     *
     * <p>Tudo numa transação: ou os três entram, ou nenhum entra.
     */
    public <T> T carregarVarios(java.util.Map<String, Path> arquivosPorTabela,
                                long execucaoId, TransformacaoMultipla<T> transformacao) {
        try (Connection conexao = dataSource.getConnection()) {
            conexao.setAutoCommit(false);
            try {
                identificarExecucao(conexao, execucaoId);
                for (var entrada : arquivosPorTabela.entrySet()) {
                    List<String> colunas = colunasDe(entrada.getValue());
                    criarTabela(conexao, entrada.getKey(), colunas);
                    long linhas = copiar(conexao, entrada.getKey(), entrada.getValue());
                    log.info("COPY de {} -> {}: {} linha(s) em {} coluna(s)",
                             entrada.getValue().getFileName(), entrada.getKey(),
                             linhas, colunas.size());
                }

                T resultado = transformacao.aplicar(conexao, arquivosPorTabela.keySet());
                conexao.commit();
                return resultado;
            } catch (Exception e) {
                conexao.rollback();
                throw new IllegalStateException(
                    "falha ao carregar " + arquivosPorTabela.values(), e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("falha de conexao ao carregar "
                                            + arquivosPorTabela.values(), e);
        }
    }

    /**
     * Sem isto, as alterações do backfill apareceriam no histórico como
     * curadoria manual — e a procedência de uma correção é justamente o que o
     * histórico existe para responder.
     */
    private static void identificarExecucao(Connection c, long execucaoId)
            throws SQLException {
        try (var ps = c.prepareStatement(
                "SELECT set_config('votecomdados.execucao_id', ?, true)")) {
            ps.setString(1, String.valueOf(execucaoId));
            ps.execute();
        }
    }

    /** Todas as colunas como TEXT: quem converte é a transformação, em SQL. */
    private static void criarTabela(Connection c, String tabela, List<String> colunas)
            throws SQLException {
        var ddl = new StringBuilder("CREATE TEMP TABLE ").append(tabela).append(" (");
        for (int i = 0; i < colunas.size(); i++) {
            if (i > 0) ddl.append(", ");
            ddl.append('"').append(colunas.get(i).replace("\"", "")).append("\" TEXT");
        }
        ddl.append(") ON COMMIT DROP");
        try (Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + tabela);
            st.execute(ddl.toString());
        }
    }

    private static long copiar(Connection c, String tabela, Path arquivo)
            throws SQLException, IOException {
        CopyManager copy = c.unwrap(PGConnection.class).getCopyAPI();
        try (var entrada = Files.newBufferedReader(arquivo, StandardCharsets.UTF_8)) {
            return copy.copyIn(
                "COPY " + tabela + " FROM STDIN WITH (FORMAT csv, HEADER true, "
                + "DELIMITER ';', QUOTE '\"')", entrada);
        }
    }

    /**
     * Nomes de coluna vêm do cabeçalho, com aspas e BOM removidos.
     *
     * <p>O BOM importa: o arquivo da Câmara começa com ele, e sem remover, a
     * primeira coluna passa a se chamar {@code ﻿id} — e a transformação
     * falha com "coluna não existe", num erro que não parece ter nada a ver.
     */
    private static List<String> colunasDe(Path arquivo) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(arquivo, StandardCharsets.UTF_8)) {
            String cabecalho = r.readLine();
            if (cabecalho == null) {
                throw new IllegalStateException("arquivo vazio: " + arquivo);
            }
            cabecalho = cabecalho.replace("﻿", "");
            var colunas = new ArrayList<String>();
            for (String bruto : cabecalho.split(";", -1)) {
                String nome = bruto.trim().replace("\"", "");
                if (nome.isEmpty()) {
                    nome = "coluna_" + colunas.size();
                }
                colunas.add(nome);
            }
            return colunas;
        }
    }

    /** O que fazer com a tabela já carregada. */
    @FunctionalInterface
    public interface Transformacao<T> {
        T aplicar(Connection conexao, String tabela) throws SQLException;
    }

    /** O que fazer com várias tabelas carregadas juntas. */
    @FunctionalInterface
    public interface TransformacaoMultipla<T> {
        T aplicar(Connection conexao, java.util.Set<String> tabelas) throws SQLException;
    }

    static String nomeNormalizado(String coluna) {
        return coluna.toLowerCase(Locale.ROOT);
    }
}
