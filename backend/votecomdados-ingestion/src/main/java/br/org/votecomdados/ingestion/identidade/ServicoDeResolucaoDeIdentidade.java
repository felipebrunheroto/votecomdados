package br.org.votecomdados.ingestion.identidade;

import br.org.votecomdados.core.dominio.Enums.Fonte;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Liga o parlamentar da Casa à pessoa da coorte.
 *
 * <h2>O maior risco do produto vive aqui</h2>
 *
 * Um vínculo errado não produz página vazia — produz uma página <b>errada</b>,
 * atribuindo a alguém o voto de outra pessoa. É o único modo de falha que
 * destrói a premissa do projeto, e é por isso que este serviço prefere
 * sistematicamente <b>não afirmar</b> a afirmar com dúvida.
 *
 * <h2>Ordem das tentativas</h2>
 *
 * <ol>
 *   <li><b>Nome civil + data de nascimento.</b> Determinístico, e cobre o caso
 *       geral: os dois campos estão 100% preenchidos no cadastro da Câmara da
 *       legislatura 54 (2011) em diante. O CPF <i>não</i> entra — a coluna vem
 *       vazia na origem (verificado em 30/08/2026).</li>
 *   <li><b>Similaridade de nome dentro da mesma UF.</b> Só quando falta a data
 *       de nascimento. Acima do limiar vira vínculo pendente de curadoria;
 *       abaixo, nada.</li>
 * </ol>
 *
 * <p>Empate nunca é desempatado por heurística. Dois candidatos plausíveis
 * viram {@link Vinculo.Desfecho#AMBIGUO} e vão para a fila do curador — fundir
 * homônimos de UFs diferentes num perfil só é o erro clássico deste domínio.
 */
@Service
public class ServicoDeResolucaoDeIdentidade {

    private final JdbcClient jdbc;
    private final BigDecimal limiar;

    ServicoDeResolucaoDeIdentidade(
            JdbcClient jdbc,
            @Value("${votecomdados.identidade.limiar-similaridade:0.85}") BigDecimal limiar) {
        this.jdbc = jdbc;
        this.limiar = limiar;
    }

    public Vinculo resolver(ParlamentarDaCasa p) {
        var jaVinculado = jdbc.sql("""
                SELECT politico_id FROM identificador_externo
                 WHERE sistema = :sistema::fonte_enum AND identificador = :id
                """)
            .param("sistema", p.fonte().name()).param("id", p.identificador())
            .query(UUID.class).optional();
        if (jaVinculado.isPresent()) {
            return Vinculo.resolvido(jaVinculado.get());
        }

        if (p.dataNascimento() != null && p.nomeCivil() != null) {
            var exatos = jdbc.sql("""
                    SELECT id FROM politico
                     WHERE data_nascimento = :nascimento
                       AND unaccent_imutavel(upper(nome_civil))
                           = unaccent_imutavel(upper(:nome))
                    """)
                .param("nascimento", p.dataNascimento())
                .param("nome", p.nomeCivil())
                .query(UUID.class).list();

            if (exatos.size() == 1) return Vinculo.resolvido(exatos.getFirst());
            if (exatos.size() > 1) {
                // Mesmo nome civil e mesma data de nascimento em duas pessoas da
                // coorte: raríssimo e não resolvível por regra. Escolher uma
                // seria apostar com o histórico de votação de alguém.
                return Vinculo.ambiguo(
                    exatos.size() + " pessoas com mesmo nome civil e nascimento");
            }
        }

        return porSimilaridade(p);
    }

    /**
     * Cauda: sem data de nascimento, resta o nome — e nome não basta sozinho.
     *
     * <p>A UF entra como âncora justamente porque homônimo em estados
     * diferentes é o caso que mais aparece. Ainda assim o resultado nunca é
     * tratado como confirmado: vai para curadoria.
     */
    private Vinculo porSimilaridade(ParlamentarDaCasa p) {
        if (p.nomeCivil() == null) return porNomeParlamentar(p);

        record Candidato(UUID id, BigDecimal score) {}
        List<Candidato> candidatos = jdbc.sql("""
                SELECT p.id,
                       similarity(unaccent_imutavel(upper(p.nome_civil)),
                                  unaccent_imutavel(upper(:nome)))::numeric AS score
                  FROM politico p
                 -- O cast é obrigatório: sem ele o Postgres não consegue
                 -- inferir o tipo de `:uf IS NULL` e recusa a consulta.
                 WHERE EXISTS (SELECT 1 FROM candidatura c
                                WHERE c.politico_id = p.id
                                  AND (:uf::text IS NULL OR c.uf = :uf::text))
                   AND similarity(unaccent_imutavel(upper(p.nome_civil)),
                                  unaccent_imutavel(upper(:nome))) >= :piso
                 ORDER BY score DESC
                 LIMIT 3
                """)
            .param("nome", p.nomeCivil())
            .param("uf", p.uf())
            .param("piso", limiar)
            .query((rs, n) -> new Candidato(
                UUID.fromString(rs.getString("id")), rs.getBigDecimal("score")))
            .list();

        if (candidatos.isEmpty()) return Vinculo.foraDaCoorte();

        // Dois nomes igualmente parecidos: não se escolhe no par ou ímpar.
        if (candidatos.size() > 1
            && candidatos.get(0).score().compareTo(candidatos.get(1).score()) == 0) {
            return Vinculo.ambiguo("empate de similaridade em "
                                   + candidatos.get(0).score());
        }

        return Vinculo.pendenteDeCuradoria(candidatos.getFirst().id(),
                                           candidatos.getFirst().score());
    }

    /**
     * Último recurso: a Casa não publica nome civil nem data de nascimento.
     *
     * <h2>É o caso da Alesp, e ele é estruturalmente mais fraco</h2>
     *
     * O cadastro da Alesp ({@code deputados.xml}) traz {@code NomeParlamentar},
     * {@code Partido} e {@code Aniversario} — e {@code Aniversario} é <b>dia e
     * mês, sem o ano</b> ("26/08"). Sem o ano não há casamento determinístico
     * possível: o par nome+nascimento que resolve Câmara e Senado simplesmente
     * não existe aqui.
     *
     * <p>Resta o nome parlamentar, comparado ao <b>nome de urna</b> do TSE —
     * que é justamente o nome pelo qual a pessoa se apresenta ao eleitorado, e
     * portanto o mais próximo do que a Casa publica. Ainda assim o desfecho
     * <b>nunca</b> é RESOLVIDO: sai como pendente de curadoria, para o owner
     * confirmar. Afirmar um vínculo com base só em nome seria exatamente o
     * risco que esta classe existe para recusar.
     *
     * <p>A UF é fixada em SP pelo chamador, o que já elimina o homônimo de
     * outro estado — o caso mais comum de erro neste domínio.
     */
    private Vinculo porNomeParlamentar(ParlamentarDaCasa p) {
        if (p.nomeParlamentar() == null) return Vinculo.foraDaCoorte();

        record Candidato(UUID id, BigDecimal score) {}
        List<Candidato> candidatos = jdbc.sql("""
                SELECT p.id,
                       greatest(
                           similarity(unaccent_imutavel(upper(coalesce(p.nome_urna, ''))),
                                      unaccent_imutavel(upper(:nome))),
                           similarity(unaccent_imutavel(upper(p.nome_civil)),
                                      unaccent_imutavel(upper(:nome)))
                       )::numeric AS score
                  FROM politico p
                 WHERE EXISTS (SELECT 1 FROM candidatura c
                                WHERE c.politico_id = p.id
                                  AND (:uf::text IS NULL OR c.uf = :uf::text))
                   AND greatest(
                           similarity(unaccent_imutavel(upper(coalesce(p.nome_urna, ''))),
                                      unaccent_imutavel(upper(:nome))),
                           similarity(unaccent_imutavel(upper(p.nome_civil)),
                                      unaccent_imutavel(upper(:nome)))
                       ) >= :piso
                 ORDER BY score DESC
                 LIMIT 3
                """)
            .param("nome", p.nomeParlamentar())
            .param("uf", p.uf())
            .param("piso", limiar)
            .query((rs, n) -> new Candidato(
                UUID.fromString(rs.getString("id")), rs.getBigDecimal("score")))
            .list();

        if (candidatos.isEmpty()) return Vinculo.foraDaCoorte();

        if (candidatos.size() > 1
            && candidatos.get(0).score().compareTo(candidatos.get(1).score()) == 0) {
            return Vinculo.ambiguo("empate de similaridade de nome parlamentar em "
                                   + candidatos.get(0).score());
        }

        return Vinculo.pendenteDeCuradoria(candidatos.getFirst().id(),
                                           candidatos.getFirst().score());
    }

    /**
     * Grava o vínculo. Fuzzy entra com {@code revisado_manualmente = false} e
     * <b>não conta como confirmado</b> até a curadoria olhar.
     */
    public void gravar(ParlamentarDaCasa p, Vinculo vinculo) {
        jdbc.sql("""
                INSERT INTO identificador_externo
                    (politico_id, sistema, identificador, metodo_resolucao, score_confianca)
                VALUES (:politico, :sistema::fonte_enum, :id,
                        :metodo::metodo_resolucao_enum, :score)
                ON CONFLICT (sistema, identificador) DO NOTHING
                """)
            .param("politico", vinculo.politicoId())
            .param("sistema", p.fonte().name())
            .param("id", p.identificador())
            .param("metodo", vinculo.metodo().name())
            .param("score", vinculo.score())
            .update();
    }

    /** Fila do curador: vínculos por similaridade ainda não revisados. */
    public long pendentesDeCuradoria() {
        return jdbc.sql("""
                SELECT count(*) FROM identificador_externo
                 WHERE metodo_resolucao = 'FUZZY' AND NOT revisado_manualmente
                """).query(Long.class).single();
    }
}
