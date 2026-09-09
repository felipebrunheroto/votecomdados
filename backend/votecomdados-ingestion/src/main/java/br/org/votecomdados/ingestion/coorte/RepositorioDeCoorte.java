package br.org.votecomdados.ingestion.coorte;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Escrita da coorte: {@code politico} e {@code candidatura}.
 *
 * <p>A coorte <b>é</b> o escopo do sistema. Só existe linha em {@code politico}
 * para quem tem registro de candidatura em 2026 — não é filtro de exibição
 * sobre uma base completa. Quem não é candidato simplesmente não tem registro
 * pessoal aqui, e essa é a posição de minimização que sustenta o argumento de
 * LGPD do projeto.
 */
@Repository
public class RepositorioDeCoorte {

    private final JdbcClient jdbc;

    RepositorioDeCoorte(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Encontra ou cria a pessoa. A ordem das tentativas não é preferência de
     * estilo — é o que faz o expurgo do CPF conviver com reexecução.
     *
     * <ol>
     *   <li><b>{@code sq_candidato_tse}</b> de uma candidatura já gravada. É a
     *       âncora <i>entre execuções</i>: sobrevive ao expurgo do
     *       {@code cpf_hmac} e é estável na origem.</li>
     *   <li><b>{@code cpf_hmac}</b>. É a âncora <i>dentro</i> de uma execução:
     *       costura as candidaturas da mesma pessoa em anos diferentes, que têm
     *       {@code sq_candidato} distintos e um só CPF.</li>
     *   <li><b>Nome civil + data de nascimento</b>, quando a origem não traz
     *       CPF. Mais fraco, e por isso o último recurso.</li>
     * </ol>
     *
     * <p><b>A ordem foi decidida por um problema real:</b> o expurgo zera o
     * {@code cpf_hmac} ao fim do job (decisão de minimização, Q11). Uma
     * resolução que dependesse só do HMAC funcionaria na primeira execução e,
     * a partir da segunda, deixaria de reconhecer as pessoas já gravadas —
     * criando duplicatas ou fragmentando trajetórias <i>em silêncio</i>. O
     * {@code sq_candidato_tse} não é apagado, e por isso vem primeiro.
     */
    /**
     * Acha a pessoa, sem criar e sem atualizar nada.
     *
     * <p>É o que os anos ANTERIORES usam: neles a candidatura só se anexa a
     * quem já está na base. Não atualizar é parte do contrato — o pacote do
     * ano da coorte entra primeiro, e deixar 2014 sobrescrever nome de urna
     * ou gênero faria o perfil exibir o dado mais VELHO.
     */
    public Optional<UUID> encontrar(CandidaturaDoTse c) {
        var porCandidatura = jdbc.sql("""
                SELECT politico_id FROM candidatura
                 WHERE sq_candidato_tse = :sq AND ano_eleicao = :ano
                """)
            .param("sq", c.sqCandidato()).param("ano", c.anoEleicao())
            .query(UUID.class).optional();
        if (porCandidatura.isPresent()) return porCandidatura;

        if (c.cpfHmac() != null) {
            var porHmac = jdbc.sql("SELECT id FROM politico WHERE cpf_hmac = :hmac")
                .param("hmac", c.cpfHmac()).query(UUID.class).optional();
            if (porHmac.isPresent()) return porHmac;
        }

        // A guarda de nascimento vive no Java, e nao no SQL.
        //
        // Antes era `AND :nascimento IS NOT NULL` dentro da consulta, e o
        // parametro aparecia DUAS vezes. No segundo uso ele nao tem contexto
        // de tipo -- nada com que o Postgres infira -- e o banco recusava com
        // "could not determine data type of parameter $3".
        //
        // Ninguem tinha visto porque este e o ultimo recurso: so se chega aqui
        // quando a pessoa nao foi achada nem por candidatura nem por CPF. Com
        // uma eleicao so, isso nunca acontecia. Derrubou a carga da coorte
        // multi-ano em 08/09/2026, ao chegar em 2016.
        //
        // Sem data de nascimento nao se tenta: nome sozinho nao desempata, e
        // em mais de um milhao de candidaturas o homonimo e certeza, nao
        // risco.
        if (c.dataNascimento() != null) {
            var porNome = jdbc.sql("""
                    SELECT id FROM politico
                     WHERE unaccent_imutavel(upper(nome_civil))
                           = unaccent_imutavel(upper(:nome))
                       AND data_nascimento = :nascimento
                    """)
                .param("nome", c.nomeCivil())
                .param("nascimento", c.dataNascimento())
                .query(UUID.class).optional();
            if (porNome.isPresent()) return porNome;
        }

        return Optional.empty();
    }

    /** Acha, atualiza o que a fonte trouxer de novo, e cria se nao existir. */
    public UUID encontrarOuCriar(CandidaturaDoTse c) {
        var achado = encontrar(c);
        if (achado.isPresent()) {
            atualizarDadosPessoais(achado.get(), c);
            return achado.get();
        }

        return jdbc.sql("""
                INSERT INTO politico (nome_civil, nome_urna, cpf_hmac, data_nascimento, genero)
                VALUES (:nome, :urna, :hmac, :nascimento, :genero)
                RETURNING id
                """)
            .param("nome", c.nomeCivil())
            .param("urna", c.nomeUrna())
            .param("hmac", c.cpfHmac())
            .param("nascimento", c.dataNascimento())
            .param("genero", c.genero())
            .query(UUID.class).single();
    }

    /**
     * Grava a candidatura. Idempotente por {@code (sq_candidato_tse, ano)}.
     *
     * <p>O {@code DO UPDATE} não é enfeite: o registro de candidatura muda até a
     * eleição — indeferimento, substituição, renúncia —, e um upsert que
     * ignorasse conflito deixaria a plataforma exibindo o status do dia da
     * primeira coleta para sempre.
     */
    public void gravarCandidatura(UUID politicoId, CandidaturaDoTse c) {
        jdbc.sql("""
                INSERT INTO candidatura
                    (politico_id, sq_candidato_tse, ano_eleicao, turno, cargo, esfera, uf,
                     municipio, codigo_municipio_tse, partido_sigla, partido_numero,
                     numero_urna, status, eleito)
                VALUES (:politico, :sq, :ano, :turno, :cargo::cargo_enum,
                        :esfera::esfera_enum, :uf, :municipio, :codigoMunicipio,
                        :partido, :partidoNumero, :numeroUrna,
                        :status::status_candidatura_enum, :eleito)
                ON CONFLICT (sq_candidato_tse, ano_eleicao) DO UPDATE SET
                    status = EXCLUDED.status,
                    eleito = EXCLUDED.eleito,
                    partido_sigla = EXCLUDED.partido_sigla,
                    partido_numero = EXCLUDED.partido_numero,
                    numero_urna = EXCLUDED.numero_urna
                """)
            .param("politico", politicoId)
            .param("sq", c.sqCandidato())
            .param("ano", c.anoEleicao())
            .param("turno", c.turno())
            .param("cargo", c.cargo().name())
            .param("esfera", c.esfera().name())
            .param("uf", c.uf())
            .param("municipio", c.municipio())
            .param("codigoMunicipio", c.codigoMunicipioTse())
            .param("partido", c.partidoSigla())
            .param("partidoNumero", c.partidoNumero())
            .param("numeroUrna", c.numeroUrna())
            .param("status", c.status().name())
            .param("eleito", c.eleito())
            .update();
    }

    /**
     * Remove quem deixou de ser candidato em 2026.
     *
     * <p>O registro de candidatura muda até a eleição, e a coorte precisa
     * acompanhar. O {@code ON DELETE CASCADE} leva junto candidaturas, votos e
     * vínculos — é o custo deliberado da minimização de dados: quem não se
     * apresenta ao eleitorado não fica arquivado aqui.
     *
     * <p>Recuperar depois é barato (reingestão pelos CSVs arquivados), então a
     * decisão erra para o lado de guardar menos.
     */
    /** Quantas candidaturas do ano existem — a base da poda. */
    public long candidaturasEm(int ano) {
        return jdbc.sql("SELECT count(*) FROM candidatura WHERE ano_eleicao = :ano")
            .param("ano", ano).query(Long.class).single();
    }

    public int podarForaDaCoorte(int anoDaCoorte) {
        return jdbc.sql("""
                DELETE FROM politico p
                 WHERE NOT EXISTS (
                     SELECT 1 FROM candidatura c
                      WHERE c.politico_id = p.id AND c.ano_eleicao = :ano
                 )
                """).param("ano", anoDaCoorte).update();
    }

    /**
     * Zera o {@code cpf_hmac} depois que a trajetória já está costurada.
     *
     * <p>Cumprido o único papel do campo — ligar candidaturas da mesma pessoa
     * entre eleições —, mantê-lo só ampliaria a superfície de dado pessoal. O
     * vínculo passa a viver em {@code identificador_externo}, e a coluna vira
     * transitória em vez de permanente.
     *
     * <p>Chamado ao FIM do job, nunca no meio: sem o HMAC, a costura da
     * trajetória não acontece. E a execução seguinte não fica órfã porque a
     * identidade entre execuções se apoia no {@code sq_candidato_tse} — ver
     * {@link #encontrarOuCriar}.
     */
    public int expurgarCpfHmac() {
        return jdbc.sql("UPDATE politico SET cpf_hmac = NULL WHERE cpf_hmac IS NOT NULL")
            .update();
    }

    private void atualizarDadosPessoais(UUID id, CandidaturaDoTse c) {
        // A candidatura mais recente manda: nome de urna e partido mudam entre
        // eleições, e o perfil deve mostrar o mais atual.
        //
        // `cpf_hmac` volta a ser preenchido de propósito. Ele é expurgado ao
        // fim de CADA execução (minimização, ARQUITETURA.md § 10), então numa
        // reexecução a base começa sem âncora nenhuma. Sem restaurá-lo aqui,
        // o pacote de uma eleição anterior não teria como casar por CPF e
        // cairia no último recurso -- nome civil + nascimento --, que é
        // justamente o que a âncora existe para evitar. Ele volta a ser
        // expurgado no `encerrar`, então não fica em repouso.
        jdbc.sql("""
                UPDATE politico
                   SET cpf_hmac = coalesce(:hmac, cpf_hmac),
                       nome_urna = coalesce(:urna, nome_urna),
                       data_nascimento = coalesce(:nascimento, data_nascimento),
                       genero = coalesce(:genero, genero),
                       atualizado_em = now()
                 WHERE id = :id
                """)
            .param("id", id)
            .param("hmac", c.cpfHmac())
            .param("urna", c.nomeUrna())
            .param("nascimento", c.dataNascimento())
            .param("genero", c.genero())
            .update();
    }
}
