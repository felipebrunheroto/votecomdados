package br.org.votecomdados.ingestion.execucao;

import br.org.votecomdados.core.dominio.Enums.Fonte;
import br.org.votecomdados.core.dominio.Enums.TipoJob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Exclusão mútua, watermark e trilha de auditoria de cada execução.
 *
 * <h2>Por que uma conexão dedicada</h2>
 *
 * O advisory lock do Postgres é de <b>sessão</b>, e o job atravessa muitas
 * transações. Pegá-lo por uma conexão de pool não funcionaria: a conexão volta
 * ao pool ao fim da primeira transação e o lock some junto, ou pior, fica preso
 * numa conexão que outra parte do código reutiliza.
 *
 * <p>Por isso o serviço segura uma conexão própria enquanto o job vive. O custo
 * é uma conexão a mais; o ganho é a propriedade que torna o reaper correto:
 *
 * <blockquote><b>Se conseguimos o lock, nenhum processo vivo está usando esta
 * fonte.</b> Uma sessão que morre — OOM, evicção, cabo puxado — libera o lock
 * na hora, mas deixa a linha {@code EM_ANDAMENTO} para trás. Segurar o lock é o
 * que autoriza limpar essa linha sem risco de estar matando um job vivo.</blockquote>
 *
 * <p>Sem essa propriedade, o reaper precisaria adivinhar por timeout — e um
 * backfill lento seria morto por um cron impaciente, com duas execuções
 * concorrentes gravando watermark uma por cima da outra (B6).
 */
@Service
public class ControleDeExecucaoService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ControleDeExecucaoService.class);

    /** Prefixo do lock, para não colidir com outro uso de advisory lock no banco. */
    private static final String ESCOPO_DO_LOCK = "ingestao:";

    private final DataSource dataSource;
    private final JdbcClient jdbc;
    private final TransactionTemplate transacao;

    /** Viva apenas entre iniciar() e concluir()/falhar(). Segura o advisory lock. */
    private Connection conexaoDoLock;

    ControleDeExecucaoService(DataSource dataSource, JdbcClient jdbc,
                              TransactionTemplate transacao) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.transacao = transacao;
    }

    /**
     * Toma a fonte para si e abre uma execução.
     *
     * @throws ExecucaoConcorrenteException se outro processo vivo já a tem
     */
    public Execucao iniciar(Fonte fonte, TipoJob tipoJob, String parametrosJson) {
        if (conexaoDoLock != null) {
            throw new IllegalStateException("este controle já tem uma execução aberta");
        }

        Connection conexao = null;
        try {
            conexao = dataSource.getConnection();
            if (!tentarLock(conexao, fonte)) {
                conexao.close();
                throw new ExecucaoConcorrenteException(fonte);
            }
            this.conexaoDoLock = conexao;
        } catch (SQLException e) {
            fecharSilenciosamente(conexao);
            throw new IllegalStateException("falha ao tomar o lock da fonte " + fonte, e);
        }

        // A partir daqui o lock é nosso, então nenhum processo vivo está nesta
        // fonte — e só por isso limpar execuções órfãs é seguro.
        int orfas = reaperDeOrfas(fonte);
        if (orfas > 0) {
            log.warn("reaper: {} execucao(oes) orfa(s) da fonte {} marcada(s) como FALHA; "
                     + "processo anterior morreu sem encerrar", orfas, fonte);
        }

        Instant watermarkAnterior = watermarkAtual(fonte).orElse(null);

        Long id = jdbc.sql("""
                INSERT INTO ingestao_execucao (fonte, tipo_job, parametros)
                VALUES (:fonte::fonte_enum, :tipo::tipo_job_enum, :parametros::jsonb)
                RETURNING id
                """)
            .param("fonte", fonte.name())
            .param("tipo", tipoJob.name())
            .param("parametros", parametrosJson)
            .query(Long.class).single();

        log.info("execucao {} iniciada: fonte={} job={} watermark_anterior={}",
                 id, fonte, tipoJob, watermarkAnterior);
        return new Execucao(id, fonte, tipoJob, watermarkAnterior);
    }

    /**
     * Fecha a execução com sucesso e avança o watermark.
     *
     * <p>O marcador <b>nunca retrocede</b>: grava-se {@code GREATEST} do valor
     * novo com o que já estava lá. Um watermark que anda para trás faria o
     * ciclo seguinte reprocessar de graça; um que anda para a frente sem que o
     * dado tenha sido processado <i>pula uma janela em silêncio</i>, que é o
     * modo de falha inaceitável.
     */
    public void concluir(Execucao execucao, Instant watermarkNovo,
                         int processados, int rejeitados) {
        jdbc.sql("""
                UPDATE ingestao_execucao
                   SET status = 'CONCLUIDA',
                       concluido_em = now(),
                       watermark_anterior = :anterior,
                       watermark_novo = GREATEST(:novo, (
                           SELECT max(watermark_novo) FROM ingestao_execucao
                            WHERE fonte = :fonte::fonte_enum AND status = 'CONCLUIDA'
                       )),
                       registros_processados = :processados,
                       registros_rejeitados = :rejeitados
                 WHERE id = :id
                """)
            .param("id", execucao.id())
            .param("fonte", execucao.fonte().name())
            .param("anterior", paraTimestamp(execucao.watermarkAnterior()))
            .param("novo", paraTimestamp(watermarkNovo))
            .param("processados", processados)
            .param("rejeitados", rejeitados)
            .update();

        log.info("execucao {} concluida: {} processados, {} em quarentena",
                 execucao.id(), processados, rejeitados);
        liberar();
    }

    /**
     * Fecha a execução com falha. O watermark fica onde estava, de propósito:
     * uma execução que morreu no meio não pode declarar a janela processada.
     */
    public void falhar(Execucao execucao, Throwable causa) {
        try {
            jdbc.sql("""
                    UPDATE ingestao_execucao
                       SET status = 'FALHA', concluido_em = now(), erro = :erro
                     WHERE id = :id
                    """)
                .param("id", execucao.id())
                .param("erro", mensagem(causa))
                .update();
            log.error("execucao {} falhou; watermark preservado em {}",
                      execucao.id(), execucao.watermarkAnterior(), causa);
        } finally {
            liberar();
        }
    }

    /** Último watermark bem-sucedido da fonte. Vazio na primeira execução. */
    public Optional<Instant> watermarkAtual(Fonte fonte) {
        return jdbc.sql("""
                SELECT max(watermark_novo) FROM ingestao_execucao
                 WHERE fonte = :fonte::fonte_enum AND status = 'CONCLUIDA'
                """)
            .param("fonte", fonte.name())
            .query(Timestamp.class).optional()
            .map(Timestamp::toInstant);
    }

    /**
     * Roda o trabalho numa transação que se identifica ao banco.
     *
     * <p>{@code SET LOCAL votecomdados.execucao_id} é o que faz os gatilhos de
     * histórico saberem QUEM alterou um voto ou uma ementa. Sem isso a
     * alteração fica registrada como manual — leitura correta para um
     * {@code UPDATE} de curadoria, e errada para o worker.
     *
     * <p>É {@code SET LOCAL} (transação), não {@code SET} (sessão), justamente
     * porque a conexão volta ao pool: um valor de sessão vazaria para o próximo
     * a usá-la, e uma alteração passaria a ser atribuída a uma execução que não
     * a fez.
     */
    public <T> T naExecucao(Execucao execucao, Supplier<T> trabalho) {
        return transacao.execute(status -> {
            jdbc.sql("SELECT set_config('votecomdados.execucao_id', :id, true)")
                .param("id", String.valueOf(execucao.id()))
                .query(String.class).single();
            return trabalho.get();
        });
    }

    /** Libera o lock ao fim do job, mesmo em caminho de erro. */
    @Override
    public void close() {
        liberar();
    }

    // ---------------------------------------------------------------- privados

    private boolean tentarLock(Connection conexao, Fonte fonte) throws SQLException {
        try (PreparedStatement ps = conexao.prepareStatement(
                 "SELECT pg_try_advisory_lock(hashtext(?)::int8)")) {
            ps.setString(1, ESCOPO_DO_LOCK + fonte.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    /**
     * Marca como FALHA o que ficou EM_ANDAMENTO sem dono.
     *
     * <p>Não há timeout aqui, e a ausência é a parte importante: já temos o
     * lock da fonte, então qualquer linha EM_ANDAMENTO que reste pertence a um
     * processo morto. Timeout seria adivinhação — e adivinhar para baixo mata
     * backfill lento, adivinhar para cima trava a fonte por horas.
     */
    private int reaperDeOrfas(Fonte fonte) {
        return jdbc.sql("""
                UPDATE ingestao_execucao
                   SET status = 'FALHA', concluido_em = now(),
                       erro = coalesce(erro, 'execucao orfa: processo encerrou sem '
                                            || 'marcar conclusao (reaper)')
                 WHERE fonte = :fonte::fonte_enum AND status = 'EM_ANDAMENTO'
                """)
            .param("fonte", fonte.name())
            .update();
    }

    private void liberar() {
        if (conexaoDoLock == null) return;
        // Fechar a sessão já libera o advisory lock; o unlock explícito existe
        // para o caso de a conexão ser devolvida a um pool em vez de fechada.
        try (Connection c = conexaoDoLock) {
            try (PreparedStatement ps = c.prepareStatement(
                     "SELECT pg_advisory_unlock_all()")) {
                ps.execute();
            }
        } catch (SQLException e) {
            log.warn("falha ao liberar o lock da execucao; a sessao sera encerrada "
                     + "e o lock cai junto", e);
        } finally {
            conexaoDoLock = null;
        }
    }

    private static void fecharSilenciosamente(Connection c) {
        if (c == null) return;
        try { c.close(); } catch (SQLException ignorado) { /* já estamos em erro */ }
    }

    private static Timestamp paraTimestamp(Instant instante) {
        return instante == null ? null : Timestamp.from(instante);
    }

    private static String mensagem(Throwable causa) {
        String m = causa.getClass().getSimpleName()
                   + (causa.getMessage() == null ? "" : ": " + causa.getMessage());
        return m.length() > 4000 ? m.substring(0, 4000) : m;
    }
}
