package br.org.votecomdados.ingestion.identidade;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Curadoria dos vínculos resolvidos por similaridade.
 *
 * <h2>Por que isto precisou existir</h2>
 *
 * O schema modela curadoria desde o início: {@code revisado_manualmente},
 * {@code revisado_por}, {@code revisado_em}, com uma restrição que impede
 * marcar como revisado sem dizer quem e quando. Mas <b>nenhum código de
 * produção jamais escreveu nessas colunas</b> — as únicas escritas estavam em
 * teste. Em 09/09/2026 havia 128 vínculos por similaridade e zero revisados,
 * sem nenhum caminho para revisá-los.
 *
 * <h2>Por que não é um {@code TipoJob}</h2>
 *
 * Curadoria não é ingestão: não lê fonte, não move watermark, não disputa a
 * exclusão mútua de execução. Fazer dela um job exigiria valor novo no enum
 * do banco e abriria uma {@code ingestao_execucao} que não descreve nada.
 * Entra antes do despacho de job, e sai.
 *
 * <h2>O que está em jogo</h2>
 *
 * Um vínculo errado atribui voto e autoria de uma pessoa a outra — o pior
 * erro que esta plataforma pode cometer. Por isso rejeitar APAGA o vínculo em
 * vez de marcá-lo revisado: deixar o vínculo e anotar "conferi que está
 * errado" manteria o dado errado em produção.
 */
@Component
public class CuradoriaDeVinculos {

    private static final Logger log = LoggerFactory.getLogger(CuradoriaDeVinculos.class);

    private final JdbcClient jdbc;

    CuradoriaDeVinculos(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Um vínculo à espera de decisão, com o que se precisa para decidir. */
    public record Pendente(String sistema, String identificador, String score,
                           String nomeCivil, String nomeUrna) {}

    public List<Pendente> pendentes() {
        return jdbc.sql("""
                SELECT ie.sistema::text AS sistema, ie.identificador,
                       ie.score_confianca::text AS score,
                       p.nome_civil, p.nome_urna
                  FROM identificador_externo ie
                  JOIN politico p ON p.id = ie.politico_id
                 WHERE ie.metodo_resolucao = 'FUZZY'
                   AND NOT ie.revisado_manualmente
                 ORDER BY ie.score_confianca ASC NULLS FIRST, ie.sistema, ie.identificador
                """)
            .query((rs, n) -> new Pendente(rs.getString("sistema"),
                                           rs.getString("identificador"),
                                           rs.getString("score"),
                                           rs.getString("nome_civil"),
                                           rs.getString("nome_urna")))
            .list();
    }

    /**
     * O vínculo está certo: fica, marcado como conferido por alguém.
     *
     * @return false se não havia vínculo pendente com esse par — dizer "não
     *         encontrei" é melhor que dizer "aprovei" sem ter aprovado nada
     */
    public boolean aprovar(String sistema, String identificador, String revisor) {
        int alterados = jdbc.sql("""
                UPDATE identificador_externo
                   SET revisado_manualmente = true,
                       revisado_por = :revisor,
                       revisado_em = now()
                 WHERE sistema = :sistema::fonte_enum
                   AND identificador = :id
                   -- So o que a similaridade decidiu precisa de gente. Marcar
                   -- um vinculo deterministico como "revisado" seria registrar
                   -- uma conferencia que ninguem precisou fazer.
                   AND metodo_resolucao = 'FUZZY'
                   AND NOT revisado_manualmente
                """)
            .param("revisor", revisor).param("sistema", sistema).param("id", identificador)
            .update();

        if (alterados > 0) {
            log.info("curadoria: {}:{} aprovado por {}", sistema, identificador, revisor);
        }
        return alterados > 0;
    }

    /**
     * O vínculo está errado: some.
     *
     * <p>Apagar, e não marcar como revisado-e-errado, porque a plataforma lê
     * `identificador_externo` para saber de quem é cada voto. Um vínculo
     * errado que permanece continua atribuindo voto à pessoa errada, por mais
     * bem documentado que esteja.
     *
     * <p>A ingestão seguinte vai tentar resolver esse identificador de novo. Se
     * cair na mesma similaridade, volta a aparecer aqui — o que é o certo:
     * quem decide é a pessoa, e a decisão não vira regra automática.
     */
    public boolean rejeitar(String sistema, String identificador, String revisor) {
        int apagados = jdbc.sql("""
                DELETE FROM identificador_externo
                 WHERE sistema = :sistema::fonte_enum
                   AND identificador = :id
                   AND metodo_resolucao = 'FUZZY'
                   AND NOT revisado_manualmente
                """)
            .param("sistema", sistema).param("id", identificador)
            .update();

        if (apagados > 0) {
            log.info("curadoria: {}:{} rejeitado por {}; vinculo removido",
                     sistema, identificador, revisor);
        }
        return apagados > 0;
    }
}
