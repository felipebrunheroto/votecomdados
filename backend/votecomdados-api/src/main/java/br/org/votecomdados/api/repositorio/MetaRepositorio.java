package br.org.votecomdados.api.repositorio;

import br.org.votecomdados.core.dominio.Enums.*;
import br.org.votecomdados.core.dominio.Modelo.StatusFonte;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MetaRepositorio {

    private final JdbcClient jdbc;

    MetaRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Frescor por fonte.
     *
     * `ultima_atualizacao` é sempre da última execução BEM-SUCEDIDA, mesmo que
     * a execução mais recente tenha falhado — o dado exibido no site é daquela
     * data. `status` reporta o desfecho da execução mais recente, para a UI
     * poder avisar que a sincronização seguinte falhou em vez de sugerir que
     * está tudo em dia.
     */
    public List<StatusFonte> statusDasFontes() {
        return jdbc.sql("""
            SELECT f.fonte,
                   (SELECT max(concluido_em) FROM ingestao_execucao e
                     WHERE e.fonte = f.fonte AND e.status = 'CONCLUIDA') AS ultima_ok,
                   (SELECT e.status::text FROM ingestao_execucao e
                     WHERE e.fonte = f.fonte
                     ORDER BY e.iniciado_em DESC LIMIT 1) AS status_recente
              FROM (SELECT DISTINCT fonte FROM ingestao_execucao) f
             WHERE EXISTS (SELECT 1 FROM ingestao_execucao e
                            WHERE e.fonte = f.fonte AND e.status = 'CONCLUIDA')
             ORDER BY f.fonte
            """)
            .query((rs, n) -> new StatusFonte(
                Fonte.valueOf(rs.getString("fonte")),
                rs.getTimestamp("ultima_ok").toInstant(),
                StatusExecucao.valueOf(rs.getString("status_recente"))))
            .list();
    }
}
