package br.org.votecomdados.ingestion.massa;

import br.org.votecomdados.core.dominio.Enums.MotivoRejeicao;
import br.org.votecomdados.ingestion.execucao.Execucao;
import br.org.votecomdados.ingestion.staging.ServicoDeQuarentena;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Carga histórica da Câmara a partir dos arquivos anuais.
 *
 * <p>A ordem é votações → votos, e não é escolha: o voto referencia a votação
 * por FK, e inverter jogaria o arquivo inteiro em quarentena.
 */
@Component
public class JobDeBackfillCamara {

    private static final Logger log = LoggerFactory.getLogger(JobDeBackfillCamara.class);

    private final CarregadorDeArquivosEmMassa carregador;
    private final ServicoDeQuarentena quarentena;
    private final ObjectMapper json;

    JobDeBackfillCamara(CarregadorDeArquivosEmMassa carregador,
                        ServicoDeQuarentena quarentena, ObjectMapper json) {
        this.carregador = carregador;
        this.quarentena = quarentena;
        this.json = json;
    }

    /**
     * Carrega {@code votacoes-AAAA.csv}.
     *
     * <p>Três decisões de mapeamento que o dado real forçou:
     *
     * <ul>
     *   <li><b>{@code votosOutros} NÃO vira {@code placar_abstencao}.</b> Numa
     *       linha real, "Outros" é 21 enquanto a descrição diz "Abstenção: 3" —
     *       o campo agrupa abstenção, obstrução e Art. 17. Mapeá-lo para
     *       abstenção reportaria um número errado com aparência de certo. O
     *       placar de abstenção fica nulo: a fonte não o publica separado.</li>
     *   <li><b>{@code idProposicao = '0'} é sentinela de ausência</b>, não um
     *       id. Sem tratar, toda votação apontaria para a proposição de id 0,
     *       que não existe, e a FK derrubaria a carga.</li>
     *   <li><b>O horário é de Brasília.</b> Gravar como UTC deslocaria a data
     *       de votações noturnas em um dia — o A11 acontecendo na ingestão em
     *       vez de na UI.</li>
     * </ul>
     *
     * <p>Todas entram como {@code SIMBOLICA}; a carga de votos promove a
     * {@code NOMINAL} quem tiver voto individual. É a definição da arquitetura:
     * votação sem registro nominal é simbólica.
     */
    public int carregarVotacoes(Execucao execucao, Path arquivo) {
        return carregador.carregarETransformar(arquivo, execucao.id(), (c, tabela) -> {
            try (Statement st = c.createStatement()) {
                return st.executeUpdate("""
                    INSERT INTO votacao (casa, id_externo, proposicao_id, data_votacao,
                                         descricao, tipo, ambito, aprovada,
                                         placar_sim, placar_nao, url_fonte)
                    SELECT 'CAMARA', l."id",
                           (SELECT p.id FROM proposicao p
                             WHERE p.casa = 'CAMARA'
                               AND p.id_externo = l."ultimaApresentacaoProposicao_idProposicao"
                               AND l."ultimaApresentacaoProposicao_idProposicao" <> '0'),
                           coalesce(
                               nullif(l."dataHoraRegistro", '')::timestamp
                                   AT TIME ZONE 'America/Sao_Paulo',
                               l."data"::date AT TIME ZONE 'America/Sao_Paulo'),
                           nullif(l."descricao", ''),
                           'SIMBOLICA',
                           CASE WHEN l."siglaOrgao" = 'PLEN' THEN 'PLENARIO'
                                ELSE 'COMISSAO' END::ambito_votacao_enum,
                           CASE l."aprovacao" WHEN '1' THEN true
                                              WHEN '0' THEN false END,
                           nullif(l."votosSim", '')::int,
                           nullif(l."votosNao", '')::int,
                           'https://www.camara.leg.br/votacoes/' || l."id"
                      FROM %s l
                     WHERE nullif(l."id", '') IS NOT NULL
                    ON CONFLICT (casa, id_externo) DO UPDATE SET
                        descricao = EXCLUDED.descricao,
                        aprovada = EXCLUDED.aprovada,
                        placar_sim = EXCLUDED.placar_sim,
                        placar_nao = EXCLUDED.placar_nao,
                        proposicao_id = coalesce(EXCLUDED.proposicao_id,
                                                 votacao.proposicao_id)
                    """.formatted(tabela));
            }
        });
    }

    /**
     * Carrega {@code votacoesVotos-AAAA.csv}.
     *
     * <p>Quem não é da coorte <b>não entra e não vira quarentena por voto</b>.
     * A votação traz ~398 linhas e a maioria é de parlamentares sem candidatura
     * em 2026; registrá-los aqui encheria a métrica de alerta com dezenas de
     * milhares de linhas por execução. Eles já foram contados uma vez, no
     * cadastro (W4), como {@code FORA_DA_COORTE}.
     */
    public ResultadoDeVotos carregarVotos(Execucao execucao, Path arquivo) {
        var naoMapeados = new ArrayList<String>();

        int gravados = carregador.carregarETransformar(arquivo, execucao.id(),
            (c, tabela) -> {
                naoMapeados.addAll(rotulosSemTraducao(c, tabela));

                int inseridos;
                try (Statement st = c.createStatement()) {
                    inseridos = st.executeUpdate("""
                        INSERT INTO voto_nominal (votacao_id, politico_id, voto,
                                                  voto_origem, origem_registro)
                        SELECT v.id, ie.politico_id, m.voto, l."voto", 'FONTE'
                          FROM %s l
                          JOIN votacao v ON v.casa = 'CAMARA'
                                        AND v.id_externo = l."idVotacao"
                          JOIN identificador_externo ie ON ie.sistema = 'CAMARA'
                                        AND ie.identificador = l."deputado_id"
                          JOIN mapeamento_voto m ON m.fonte = 'CAMARA'
                                        AND m.valor_origem = l."voto"
                        ON CONFLICT (votacao_id, politico_id) DO UPDATE SET
                            voto = EXCLUDED.voto,
                            voto_origem = EXCLUDED.voto_origem,
                            origem_registro = EXCLUDED.origem_registro
                        """.formatted(tabela));

                    // Nominal ou simbólica é fato sobre o PROCEDIMENTO DA CASA,
                    // e por isso se decide pelo ARQUIVO — não pelas linhas que
                    // guardamos.
                    //
                    // A primeira versão olhava para `voto_nominal`, e estava
                    // errada: como só gravamos votos da coorte, uma votação
                    // nominal em que ninguém da coorte votou ficaria marcada
                    // como simbólica. O efeito era silencioso e grave — a
                    // derivação ignora simbólicas, então a ausência de quem
                    // estava em exercício naquele dia simplesmente sumiria.
                    // Nosso recorte de escopo teria virado uma afirmação falsa
                    // sobre como a Câmara votou.
                    st.executeUpdate("""
                        UPDATE votacao v SET tipo = 'NOMINAL'
                         WHERE v.casa = 'CAMARA' AND v.tipo = 'SIMBOLICA'
                           AND EXISTS (SELECT 1 FROM %s l
                                        WHERE l."idVotacao" = v.id_externo)
                        """.formatted(tabela));
                }
                return inseridos;
            });

        for (String rotulo : naoMapeados) {
            // Rótulo sem tradução não é adivinhado: preferimos não classificar
            // a classificar errado, e o caso vira trabalho visível.
            quarentena.rejeitar(execucao, "voto", rotulo,
                                MotivoRejeicao.VALOR_VOTO_NAO_MAPEADO,
                                "rotulo de voto sem traducao em mapeamento_voto",
                                json.createObjectNode().put("voto", rotulo));
        }

        if (!naoMapeados.isEmpty()) {
            log.warn("{} rotulo(s) de voto sem traducao: {}", naoMapeados.size(),
                     naoMapeados);
        }
        return new ResultadoDeVotos(gravados, naoMapeados);
    }

    private static List<String> rotulosSemTraducao(Connection c, String tabela)
            throws SQLException {
        var rotulos = new ArrayList<String>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("""
                SELECT DISTINCT l."voto"
                  FROM %s l
                  LEFT JOIN mapeamento_voto m ON m.fonte = 'CAMARA'
                                             AND m.valor_origem = l."voto"
                 WHERE m.id IS NULL AND nullif(l."voto", '') IS NOT NULL
                """.formatted(tabela))) {
            while (rs.next()) rotulos.add(rs.getString(1));
        }
        return rotulos;
    }

    /**
     * Carrega proposições, temas e autoria — os três juntos, porque dependem
     * uns dos outros.
     *
     * <h3>Só entram as matérias com autoria na coorte</h3>
     *
     * O arquivo anual traz todas as proposições do ano; a plataforma mostra a
     * atuação de quem é candidato em 2026. Guardar as demais encheria o banco
     * de matérias que nenhuma página exibe. A lista de autores de uma matéria
     * que entra, porém, vai <b>completa</b> — omitir coautor distorceria o
     * registro. Quem não é da coorte entra como nome, sem perfil.
     *
     * <h3>O upsert corrige a ementa (A5)</h3>
     *
     * A versão anterior deste pipeline atualizava só situação e URL. Se a
     * Câmara corrigisse o texto de uma ementa, a plataforma exibiria a versão
     * errada para sempre. Agora a ementa é atualizada — e o gatilho de
     * histórico guarda o que estava lá antes, para que "atualizar" não vire
     * "perder".
     */
    public ResultadoDeProposicoes carregarProposicoes(Execucao execucao, Path proposicoes,
                                                      Path temas, Path autores) {
        var arquivos = new java.util.LinkedHashMap<String, Path>();
        arquivos.put("prop", proposicoes);
        arquivos.put("tema", temas);
        arquivos.put("autor", autores);

        return carregador.carregarVarios(arquivos, execucao.id(), (c, tabelas) -> {
            try (Statement st = c.createStatement()) {
                int materias = st.executeUpdate("""
                    INSERT INTO proposicao (casa, id_externo, sigla_tipo, numero, ano,
                                            ementa, data_apresentacao, situacao_atual,
                                            url_inteiro_teor, url_tramitacao)
                    SELECT 'CAMARA', p."id", p."siglaTipo",
                           nullif(p."numero", '')::int,
                           p."ano"::smallint,
                           coalesce(nullif(p."ementa", ''),
                                    nullif(p."ementaDetalhada", '')),
                           nullif(p."dataApresentacao", '')::timestamp::date,
                           nullif(p."ultimoStatus_descricaoSituacao", ''),
                           nullif(p."urlInteiroTeor", ''),
                           'https://www.camara.leg.br/proposicoesWeb/fichadetramitacao'
                               || '?idProposicao=' || p."id"
                      FROM prop p
                     WHERE coalesce(nullif(p."ementa", ''),
                                    nullif(p."ementaDetalhada", '')) IS NOT NULL
                       AND EXISTS (
                           SELECT 1 FROM autor a
                             JOIN identificador_externo ie
                               ON ie.sistema = 'CAMARA'
                              AND ie.identificador = nullif(a."idDeputadoAutor", '')
                            WHERE a."idProposicao" = p."id")
                    ON CONFLICT (casa, id_externo) DO UPDATE SET
                        ementa = EXCLUDED.ementa,
                        situacao_atual = EXCLUDED.situacao_atual,
                        url_inteiro_teor = EXCLUDED.url_inteiro_teor,
                        atualizado_em = now()
                    """);

                // Tema é substituído, não somado: um tema retirado na origem
                // precisa sumir daqui. Só somar deixaria a classificação antiga
                // colada para sempre.
                st.executeUpdate("""
                    DELETE FROM proposicao_tema pt
                     USING proposicao p
                     WHERE pt.proposicao_id = p.id AND p.casa = 'CAMARA'
                       AND p.id_externo IN (SELECT "id" FROM prop)
                    """);

                // O arquivo de temas NÃO traz o id da proposição — só a URI.
                // Extrair o id dela é obrigatório, e a ausência da coluna é o
                // tipo de detalhe que só aparece abrindo o arquivo.
                int classificacoes = st.executeUpdate("""
                    INSERT INTO proposicao_tema (proposicao_id, tema)
                    SELECT p.id, t."tema"
                      FROM tema t
                      JOIN proposicao p ON p.casa = 'CAMARA'
                       AND p.id_externo = regexp_replace(t."uriProposicao", '^.*/', '')
                     WHERE nullif(t."tema", '') IS NOT NULL
                    ON CONFLICT DO NOTHING
                    """);

                // `idDeputadoAutor` vem VAZIO quando o autor é senador, órgão
                // ou o Executivo — e também quando é deputado fora da coorte.
                // Nos dois casos vira nome sem perfil: a lista de autoria fica
                // completa, e só quem se apresenta ao eleitorado tem página.
                int autorias = st.executeUpdate("""
                    INSERT INTO proposicao_autor (proposicao_id, politico_id,
                                                  autor_nome, autor_principal)
                    SELECT p.id, ie.politico_id, a."nomeAutor",
                           coalesce(a."proponente" = '1', true)
                      FROM autor a
                      JOIN proposicao p ON p.casa = 'CAMARA'
                       AND p.id_externo = a."idProposicao"
                      LEFT JOIN identificador_externo ie
                             ON ie.sistema = 'CAMARA'
                            AND ie.identificador = nullif(a."idDeputadoAutor", '')
                     WHERE nullif(a."nomeAutor", '') IS NOT NULL
                    ON CONFLICT (proposicao_id, autor_nome) DO UPDATE SET
                        politico_id = EXCLUDED.politico_id,
                        autor_principal = EXCLUDED.autor_principal
                    """);

                return new ResultadoDeProposicoes(materias, classificacoes, autorias);
            }
        });
    }

    public record ResultadoDeVotos(int gravados, List<String> rotulosSemTraducao) {}

    public record ResultadoDeProposicoes(int materias, int temas, int autorias) {}
}
