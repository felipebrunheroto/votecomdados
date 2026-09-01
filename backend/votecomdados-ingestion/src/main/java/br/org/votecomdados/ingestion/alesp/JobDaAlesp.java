package br.org.votecomdados.ingestion.alesp;

import br.org.votecomdados.core.dominio.Enums.MotivoRejeicao;
import br.org.votecomdados.ingestion.execucao.Execucao;
import br.org.votecomdados.ingestion.staging.RepositorioDePayloadBruto;
import br.org.votecomdados.ingestion.staging.ServicoDeQuarentena;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Carga da Alesp: proposituras, autoria e votos de comissão.
 *
 * <h2>Só existe voto de COMISSÃO, e isso não é detalhe</h2>
 *
 * A Alesp publica votação nominal de plenário apenas como <b>PDF por
 * votação</b>, com imagem embutida — verificado em 31/08/2026 na API do portal.
 * O registro existe e não é legível por máquina. O que entra aqui é o voto
 * individual em comissão permanente, que não tem o mesmo peso político de uma
 * deliberação de plenário: daí {@code ambito = 'COMISSAO'} em toda votação
 * gravada por este job.
 *
 * <h2>O voto é mapeado pelo CÓDIGO, não pelo texto</h2>
 *
 * O arquivo traz {@code <TipoVoto>} — código de uma letra, 8 valores
 * documentados pela própria Alesp — e {@code <Voto>}, que a documentação dela
 * chama de "descrição do tipo do voto": 477 textos distintos em 226 mil votos,
 * com erros de digitação e frases inteiras. Mapear pelo texto deixaria ~1% dos
 * votos em quarentena permanente e crescendo. Os dois são preservados:
 * {@code voto_origem_codigo} guarda o código, {@code voto_origem} o texto.
 *
 * <h2>F e P podem ser opostos, e aí não se decide no chute</h2>
 *
 * {@code F} é favorável ao <b>parecer</b> do relator; {@code P}, favorável ao
 * <b>projeto</b>. A Casa usa um par ou o outro na mesma deliberação — F/C ou
 * P/T —, e C e T nunca coexistem (0 casos em 29.923 deliberações). Mas quando
 * F e P coexistem (36 deliberações, 0,12%) eles podem ser <b>opostos</b>: há
 * deliberação com "Favorável ao projeto e contrário ao parecer" codificado
 * como P ao lado de um F. Gravar ambos como SIM diria que sete parlamentares
 * votaram igual quando quatro votaram contra os outros três.
 *
 * <p>Essas deliberações vão inteiras para quarentena. É o mesmo princípio do
 * B5, um nível acima: a ambiguidade não é do código, é da deliberação.
 *
 * <h2>Nada aqui é derivado</h2>
 *
 * A Alesp publica o voto de cada membro presente da comissão, e não a lista de
 * quem faltou. Não há como derivar ausência sem saber a composição da comissão
 * na data — e a fonte não publica isso por data. Ausência em comissão
 * simplesmente não é afirmada.
 */
@Component
public class JobDaAlesp {

    private static final Logger log = LoggerFactory.getLogger(JobDaAlesp.class);

    /** Data que a Alesp usa como "sem data de publicação". */
    private static final String DATA_SENTINELA = "0001";

    private final JdbcClient jdbc;
    private final RepositorioDePayloadBruto staging;
    private final ServicoDeQuarentena quarentena;
    private final ObjectMapper json;

    JobDaAlesp(JdbcClient jdbc, RepositorioDePayloadBruto staging,
               ServicoDeQuarentena quarentena, ObjectMapper json) {
        this.jdbc = jdbc;
        this.staging = staging;
        this.quarentena = quarentena;
        this.json = json;
    }

    // ------------------------------------------------------------------------
    // Tabelas de apoio: pequenas, ficam em memória
    // ------------------------------------------------------------------------

    /** {@code IdNatureza} -> sigla ("PL", "PEC", "Indicação"...). 106 linhas. */
    public Map<String, String> lerNaturezas(Iterable<? extends JsonNode> naturezas) {
        var mapa = new HashMap<String, String>();
        for (JsonNode n : naturezas) {
            String id = texto(n, "idNatureza");
            if (id == null) continue;
            // Nem toda natureza tem sigla; o nome é o rótulo de fallback, e é
            // preferível a um número solto na tela.
            String sigla = coalesce(texto(n, "sgNatureza"), texto(n, "nmNatureza"));
            if (sigla != null) mapa.put(id, sigla);
        }
        return mapa;
    }

    /**
     * {@code IdReuniao} -> data. É o arquivo de reuniões que datava a votação:
     * o de votos não tem campo de data nenhum.
     */
    public Map<String, LocalDate> lerReunioes(Execucao execucao,
                                              Iterable<? extends JsonNode> reunioes) {
        var mapa = new HashMap<String, LocalDate>();
        for (JsonNode r : reunioes) {
            String id = texto(r, "IdReuniao");
            String data = texto(r, "Data");
            if (id == null || data == null) continue;
            staging.gravar(execucao, "reuniao_comissao", id, r);
            mapa.put(id, LocalDate.parse(data.substring(0, 10)));
        }
        return mapa;
    }

    // ------------------------------------------------------------------------
    // Proposituras
    // ------------------------------------------------------------------------

    /**
     * Carrega as proposituras.
     *
     * <p>Dois defeitos da fonte tratados aqui, ambos verificados nos 278.014
     * registros reais:
     *
     * <ul>
     *   <li><b>12.413 IdDocumento aparecem duas vezes</b>, e as linhas são
     *       byte a byte idênticas. O upsert absorve, mas a contagem de
     *       processados não pode contá-las duas vezes.</li>
     *   <li><b>{@code DtPublicacao} vem como {@code 0001-01-01}</b> em 6
     *       registros — sentinela de "sem data". Gravá-la como data real
     *       poria uma matéria de 2022 no ano 1 na linha do tempo.</li>
     * </ul>
     */
    public Resultado carregarProposituras(Execucao execucao,
                                          Iterable<? extends JsonNode> proposituras,
                                          Map<String, String> naturezas) {
        int gravadas = 0, duplicadas = 0, rejeitadas = 0;
        var vistas = new HashSet<String>();

        for (JsonNode p : proposituras) {
            String id = texto(p, "IdDocumento");
            if (id == null) {
                quarentena.rejeitar(execucao, "proposicao", null,
                                    MotivoRejeicao.PAYLOAD_INVALIDO,
                                    "propositura sem IdDocumento", p);
                rejeitadas++;
                continue;
            }
            if (!vistas.add(id)) {
                duplicadas++;
                continue;
            }
            staging.gravar(execucao, "proposicao", id, p);

            String ementa = texto(p, "Ementa");
            if (ementa == null) {
                quarentena.rejeitar(execucao, "proposicao", id,
                                    MotivoRejeicao.PAYLOAD_INVALIDO,
                                    "propositura sem ementa", p);
                rejeitadas++;
                continue;
            }

            String publicacao = texto(p, "DtPublicacao");
            String apresentacao =
                (publicacao != null && !publicacao.startsWith(DATA_SENTINELA))
                    ? publicacao.substring(0, 10) : null;

            jdbc.sql("""
                    INSERT INTO proposicao (casa, id_externo, sigla_tipo, numero, ano,
                                            ementa, data_apresentacao, url_tramitacao)
                    VALUES ('ALESP', :id, :sigla, :numero, :ano, :ementa,
                            :apresentacao::date, :url)
                    ON CONFLICT (casa, id_externo) DO UPDATE SET
                        sigla_tipo = EXCLUDED.sigla_tipo,
                        numero = EXCLUDED.numero,
                        ano = EXCLUDED.ano,
                        ementa = EXCLUDED.ementa,
                        data_apresentacao = EXCLUDED.data_apresentacao,
                        atualizado_em = now()
                    """)
                .param("id", id)
                .param("sigla", naturezas.getOrDefault(texto(p, "IdNatureza"), "Documento"))
                .param("numero", inteiro(texto(p, "NroLegislativo")))
                .param("ano", Integer.parseInt(texto(p, "AnoLegislativo")))
                .param("ementa", ementa)
                .param("apresentacao", apresentacao)
                .param("url", ArquivosDaAlesp.urlDaPropositura(id))
                .update();
            gravadas++;
        }

        log.info("alesp: {} proposituras gravadas, {} linhas duplicadas na origem, "
                 + "{} rejeitadas", gravadas, duplicadas, rejeitadas);
        return new Resultado(gravadas, duplicadas, rejeitadas);
    }

    // ------------------------------------------------------------------------
    // Autoria
    // ------------------------------------------------------------------------

    /**
     * Carrega a autoria.
     *
     * <p>O arquivo cobre 884 mil documentos, contra 265 mil proposituras: a
     * maioria dos vínculos aponta para documento que não é propositura
     * (pareceres, ofícios). Esses são ignorados sem quarentena — não é defeito,
     * é recorte.
     *
     * <p>{@code autor_nome} é sempre gravado; {@code politico_id} só quando o
     * autor é da coorte. Autores institucionais ("Governador", que assina 19.887
     * matérias) entram como nome, sem perfil — que é a regra de
     * {@code proposicao_autor}.
     */
    public int carregarAutoria(Execucao execucao, Iterable<? extends JsonNode> autorias) {
        int gravadas = 0;
        var proposicoes = new HashMap<String, Long>();
        var politicos = new HashMap<String, UUID>();

        for (JsonNode a : autorias) {
            String documento = texto(a, "IdDocumento");
            String nome = texto(a, "NomeAutor");
            if (documento == null || nome == null) continue;

            Long proposicao = proposicoes.computeIfAbsent(documento, this::proposicaoDe);
            if (proposicao == null) continue;   // documento que não é propositura

            String idAutor = texto(a, "IdAutor");
            UUID politico = idAutor == null ? null
                : politicos.computeIfAbsent(idAutor, this::politicoDe);

            jdbc.sql("""
                    INSERT INTO proposicao_autor (proposicao_id, politico_id, autor_nome)
                    VALUES (:proposicao, :politico, :nome)
                    ON CONFLICT (proposicao_id, autor_nome) DO UPDATE SET
                        politico_id = EXCLUDED.politico_id
                    """)
                .param("proposicao", proposicao)
                .param("politico", politico)
                .param("nome", nome)
                .update();
            gravadas++;
        }
        log.info("alesp: {} vinculos de autoria", gravadas);
        return gravadas;
    }

    // ------------------------------------------------------------------------
    // Votos de comissão
    // ------------------------------------------------------------------------

    /**
     * Carrega os votos de comissão, agrupados por deliberação.
     *
     * <p>A unidade de votação da Alesp é o par {@code (IdReuniao, IdDocumento)}:
     * a deliberação de UMA matéria dentro de UMA reunião. O agrupamento é feito
     * em memória e não por ordem do arquivo — a fonte não promete ordenação, e
     * depender dela produziria votação partida ao meio no dia em que mudasse.
     */
    public ResultadoVotacoes carregarVotos(Execucao execucao,
                                           Iterable<? extends JsonNode> votos,
                                           Map<String, LocalDate> datasDeReuniao) {
        var porDeliberacao = new LinkedHashMap<Deliberacao, List<JsonNode>>();
        for (JsonNode v : votos) {
            String reuniao = texto(v, "IdReuniao");
            String documento = texto(v, "IdDocumento");
            if (reuniao == null || documento == null) {
                quarentena.rejeitar(execucao, "voto", null,
                                    MotivoRejeicao.PAYLOAD_INVALIDO,
                                    "voto sem IdReuniao ou IdDocumento", v);
                continue;
            }
            porDeliberacao.computeIfAbsent(new Deliberacao(reuniao, documento),
                                           k -> new ArrayList<>()).add(v);
        }

        int votacoes = 0, gravados = 0, semData = 0, ambiguas = 0;
        var codigosSemTraducao = new HashSet<String>();
        var traducoes = traducoesDaAlesp();

        for (var entrada : porDeliberacao.entrySet()) {
            Deliberacao d = entrada.getKey();
            List<JsonNode> linhas = entrada.getValue();

            LocalDate data = datasDeReuniao.get(d.reuniao());
            if (data == null) {
                // 361 votos em 3 reuniões ausentes do arquivo de reuniões
                // (31/08/2026). Sem data não há como situar a votação na
                // linha do tempo, e uma votação sem data mentiria sobre
                // QUANDO o parlamentar votou.
                quarentena.rejeitar(execucao, "voto", d.idExterno(),
                                    MotivoRejeicao.VOTACAO_DESCONHECIDA,
                                    "reuniao " + d.reuniao()
                                    + " ausente do arquivo de reunioes: votacao sem data",
                                    linhas.getFirst());
                semData += linhas.size();
                continue;
            }

            if (ambigua(linhas)) {
                quarentena.rejeitar(execucao, "voto", d.idExterno(),
                                    MotivoRejeicao.VALOR_VOTO_NAO_MAPEADO,
                                    "deliberacao mistura F (favoravel ao parecer) e "
                                    + "P (favoravel ao projeto), que podem ser opostos "
                                    + "na mesma votacao",
                                    linhas.getFirst());
                ambiguas++;
                continue;
            }

            long votacaoId = gravarVotacao(execucao, d, data, linhas);
            votacoes++;
            gravados += gravarVotos(votacaoId, linhas, traducoes, codigosSemTraducao);
        }

        for (String codigo : codigosSemTraducao) {
            quarentena.rejeitar(execucao, "voto", codigo,
                                MotivoRejeicao.VALOR_VOTO_NAO_MAPEADO,
                                "codigo de voto da Alesp sem traducao em mapeamento_voto",
                                json.createObjectNode().put("TipoVoto", codigo));
        }
        if (!codigosSemTraducao.isEmpty()) {
            log.warn("{} codigo(s) de voto da Alesp sem traducao: {}",
                     codigosSemTraducao.size(), codigosSemTraducao);
        }
        if (ambiguas > 0) {
            log.warn("{} deliberacao(oes) com F e P misturados foram para quarentena: "
                     + "a fonte nao diz qual dos dois é o voto vencedor", ambiguas);
        }
        return new ResultadoVotacoes(votacoes, gravados, semData, ambiguas,
                                     List.copyOf(codigosSemTraducao));
    }

    /**
     * F e P na mesma deliberação: um vota o parecer, o outro vota o projeto.
     *
     * <p>Verificado nos dados: a fonte emite "Favorável ao projeto e contrário
     * ao parecer" com código P, ao lado de linhas F na mesma deliberação. Os
     * dois são "favorável" no rótulo e opostos no efeito.
     */
    private static boolean ambigua(List<JsonNode> linhas) {
        boolean temF = false, temP = false;
        for (JsonNode l : linhas) {
            String c = texto(l, "TipoVoto");
            if ("F".equals(c)) temF = true;
            if ("P".equals(c)) temP = true;
        }
        return temF && temP;
    }

    /**
     * A Alesp <b>não publica o resultado</b> da deliberação — só os votos.
     *
     * <p>Por isso {@code aprovada} fica nula. Contar os votos e concluir
     * "aprovada" seria apurar uma votação em nome da Casa: em comissão há
     * quórum, voto do presidente e regra de desempate que a fonte não expõe.
     * Nulo é o que a fonte sustenta.
     */
    private long gravarVotacao(Execucao execucao, Deliberacao d, LocalDate data,
                               List<JsonNode> linhas) {
        staging.gravar(execucao, "voto", d.idExterno(), linhas.getFirst());
        Long proposicao = proposicaoDe(d.documento());

        return jdbc.sql("""
                INSERT INTO votacao (casa, id_externo, proposicao_id, data_votacao,
                                     descricao, tipo, ambito, aprovada, url_fonte)
                VALUES ('ALESP', :id, :proposicao,
                        :data::date AT TIME ZONE 'America/Sao_Paulo',
                        :descricao, 'NOMINAL', 'COMISSAO', NULL, :url)
                ON CONFLICT (casa, id_externo) DO UPDATE SET
                    proposicao_id = EXCLUDED.proposicao_id,
                    data_votacao = EXCLUDED.data_votacao,
                    descricao = EXCLUDED.descricao
                RETURNING id
                """)
            .param("id", d.idExterno())
            .param("proposicao", proposicao)
            .param("data", data.toString())
            .param("descricao", "Deliberação em comissão permanente da Alesp")
            .param("url", ArquivosDaAlesp.urlDaReuniao(d.reuniao()))
            .query(Long.class).single();
    }

    private int gravarVotos(long votacaoId, List<JsonNode> linhas,
                            Map<String, String> traducoes, Set<String> semTraducao) {
        int gravados = 0;
        for (JsonNode l : linhas) {
            String codigo = texto(l, "TipoVoto");
            String idSpl = texto(l, "IdDeputado");   // é o id do SPL, não o do portal
            if (codigo == null || idSpl == null) continue;

            UUID politico = politicoDe(idSpl);
            if (politico == null) continue;   // fora da coorte

            String normalizado = traducoes.get(codigo);
            if (normalizado == null) {
                // Código 'O' ("Outros") cai aqui de propósito: é a própria
                // fonte dizendo "não classificado".
                semTraducao.add(codigo);
                continue;
            }

            jdbc.sql("""
                    INSERT INTO voto_nominal (votacao_id, politico_id, voto,
                                              voto_origem, voto_origem_codigo,
                                              origem_registro)
                    VALUES (:votacao, :politico, :voto::tipo_voto_enum,
                            :origem, :codigo, 'FONTE')
                    ON CONFLICT (votacao_id, politico_id) DO UPDATE SET
                        voto = EXCLUDED.voto,
                        voto_origem = EXCLUDED.voto_origem,
                        voto_origem_codigo = EXCLUDED.voto_origem_codigo,
                        origem_registro = EXCLUDED.origem_registro
                    """)
                .param("votacao", votacaoId)
                .param("politico", politico)
                .param("voto", normalizado)
                // O texto livre é o que a UI mostra em "registrado como". É o
                // fato mais rico que a fonte publica, e o código não o
                // substitui.
                .param("origem", coalesce(texto(l, "Voto"), codigo))
                .param("codigo", codigo)
                .update();
            gravados++;
        }
        return gravados;
    }

    private Map<String, String> traducoesDaAlesp() {
        var mapa = new HashMap<String, String>();
        jdbc.sql("SELECT valor_origem, voto::text FROM mapeamento_voto WHERE fonte = 'ALESP'")
            .query((rs, n) -> mapa.put(rs.getString(1), rs.getString(2)))
            .list();
        return mapa;
    }

    private Long proposicaoDe(String idDocumento) {
        return jdbc.sql("""
                SELECT id FROM proposicao WHERE casa = 'ALESP' AND id_externo = :id
                """).param("id", idDocumento).query(Long.class).optional().orElse(null);
    }

    private UUID politicoDe(String idSpl) {
        return jdbc.sql("""
                SELECT politico_id FROM identificador_externo
                 WHERE sistema = 'ALESP' AND identificador = :id
                """).param("id", idSpl).query(UUID.class).optional().orElse(null);
    }

    private static Integer inteiro(String s) {
        if (s == null) return null;
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String coalesce(String... valores) {
        for (String v : valores) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static String texto(JsonNode no, String campo) {
        JsonNode v = no.get(campo);
        if (v == null || v.isNull()) return null;
        String s = v.asString().trim();
        return s.isEmpty() ? null : s;
    }

    /** A unidade de votação da Alesp: uma matéria dentro de uma reunião. */
    private record Deliberacao(String reuniao, String documento) {
        String idExterno() {
            return reuniao + "-" + documento;
        }
    }

    public record Resultado(int gravadas, int duplicadasNaOrigem, int rejeitadas) {}

    public record ResultadoVotacoes(int votacoes, int votos, int votosSemData,
                                    int deliberacoesAmbiguas,
                                    List<String> codigosSemTraducao) {}
}
