package br.org.votecomdados.ingestion.publicacao;

import br.org.votecomdados.ingestion.execucao.Execucao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Os dois passos que fecham QUALQUER ingestão bem-sucedida, sempre juntos e
 * sempre nesta ordem.
 *
 * <h2>Por que os dois são um serviço só, e não duas chamadas soltas</h2>
 *
 * {@code perfil_leitura} copia {@code politico.possui_atuacao_legislativa}.
 * Reconstruir a projeção antes de recalcular a atuação publicaria o valor
 * <b>velho</b> — e é exatamente o tipo de erro que só aparece meses depois,
 * quando alguém notar um perfil "sem atuação" que na verdade tem voto
 * registrado. Antes deste serviço existir, cada job de ingestão chamava
 * {@link ProjecaoDeLeitura} diretamente, e o achado A1 (01/09/2026) foi
 * justamente que nenhum deles chamava a marcação — porque ela não existia.
 *
 * <p>Unir os dois numa classe só torna a ordem impossível de inverter por
 * engano num job novo: quem depende do fim da ingestão depende deste serviço,
 * não dos dois passos internos.
 */
@Service
public class FinalizadorDeIngestao {

    private static final Logger log = LoggerFactory.getLogger(FinalizadorDeIngestao.class);

    private final JdbcClient jdbc;
    private final ProjecaoDeLeitura projecao;

    FinalizadorDeIngestao(JdbcClient jdbc, ProjecaoDeLeitura projecao) {
        this.jdbc = jdbc;
        this.projecao = projecao;
    }

    /**
     * @return quantos perfis a projeção reconstruída cobre
     */
    public long finalizar(Execucao execucao) {
        long alterados = jdbc.sql("SELECT marcar_atuacao_legislativa()")
            .query(Long.class).single();
        log.info("atuacao legislativa recalculada: {} politico(s) mudaram de estado",
                 alterados);

        return projecao.reconstruir(execucao);
    }
}
