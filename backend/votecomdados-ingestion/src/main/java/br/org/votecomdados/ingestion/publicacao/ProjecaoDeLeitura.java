package br.org.votecomdados.ingestion.publicacao;

import br.org.votecomdados.ingestion.execucao.Execucao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Reconstrói {@code perfil_leitura} ao fim da ingestão.
 *
 * <p>É o passo que faz a página de perfil sair de um {@code SELECT} por chave
 * primária em vez de três consultas — a de cobertura com window function sobre
 * join. O que sustenta a projeção é o p95: com o tráfego espalhado por milhares
 * de páginas o cache de borda fica frio, e quase toda visita paga a consulta
 * inteira num banco burstable.
 *
 * <p>Roda <b>depois</b> de tudo, inclusive da derivação de ausência: a projeção
 * é foto do estado final, e reconstruí-la no meio publicaria um perfil sem os
 * votos que ainda iam entrar.
 */
@Service
public class ProjecaoDeLeitura {

    private static final Logger log = LoggerFactory.getLogger(ProjecaoDeLeitura.class);

    private final JdbcClient jdbc;

    ProjecaoDeLeitura(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Reconstrução total, não incremental.
     *
     * <p>A coorte tem ~28 mil pessoas e a operação leva segundos. Rastrear o
     * que mudou economizaria pouco e traria de volta o risco que a projeção já
     * tem: divergir da origem em silêncio.
     */
    public long reconstruir(Execucao execucao) {
        long perfis = jdbc.sql("SELECT reconstruir_perfil_leitura(:execucao)")
            .param("execucao", execucao.id())
            .query(Long.class).single();

        long politicos = jdbc.sql("SELECT count(*) FROM politico")
            .query(Long.class).single();

        if (perfis != politicos) {
            // Projeção que não cobre todo mundo é bug de pipeline: a API cai no
            // caminho normalizado e serve dado correto, mas mais devagar — e o
            // aviso precisa existir, senão o fallback esconde a falha.
            log.warn("projecao cobriu {} de {} politicos; alguem ficaria sem perfil "
                     + "pre-calculado", perfis, politicos);
        } else {
            log.info("projecao de leitura reconstruida: {} perfis", perfis);
        }
        return perfis;
    }
}
