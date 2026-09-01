package br.org.votecomdados.ingestion.staging;

import br.org.votecomdados.core.dominio.Enums.Fonte;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Deixa passar só o que foi declarado. Tudo o mais é descartado.
 *
 * <h2>Allowlist, não denylist — e a diferença é o achado B1</h2>
 *
 * O dataset de candidaturas do TSE traz {@code NR_CPF_CANDIDATO}. Gravar o
 * payload como veio criaria uma base de CPFs em claro de ~28 mil pessoas, num
 * banco cuja credencial de escrita o worker carrega — exatamente o que a
 * arquitetura declara não existir.
 *
 * <p>Uma denylist ("apague o CPF") protegeria contra o campo que já
 * conhecemos. A allowlist protege contra o que ainda não conhecemos: quando a
 * fonte acrescentar um campo novo — e fontes de governo acrescentam sem avisar
 * —, ele entra como <b>ignorado</b>, não como vazamento. O custo é ter de
 * declarar cada campo que interessa; é o custo certo.
 *
 * <p>O CPF é usado, sim: vira {@code cpf_hmac} <b>em memória</b>, antes desta
 * classe ser chamada. O que nunca acontece é ele tocar o disco.
 */
@Component
public class RedatorDeCamposSensiveis {

    /**
     * Campos preservados por (fonte, recurso). Nomes conferidos contra os
     * arquivos reais das fontes — ver db/golden/.
     *
     * <p>Acrescentar campo aqui é decisão consciente e revisável em diff, que é
     * o ponto: a lista é curta o suficiente para ser lida por inteiro numa
     * revisão de código.
     */
    private static final Map<String, Set<String>> PERMITIDOS = Map.ofEntries(
        // TSE — candidaturas. NR_CPF_CANDIDATO e NR_TITULO_ELEITORAL_CANDIDATO
        // NÃO estão aqui, e é o caso que motivou a classe inteira.
        // Conferida contra as 50 colunas de consulta_cand_2026 em 31/08/2026.
        // Ficam de fora, e cada uma por um motivo: NR_CPF_CANDIDATO e
        // NR_TITULO_ELEITORAL_CANDIDATO são identificadores pessoais;
        // DS_EMAIL é contato pessoal — este a allowlist barrou sem que ninguém
        // tivesse pensado nele, que é exatamente o argumento dela contra a
        // denylist.
        Map.entry("TSE:candidatura", Set.of(
            "SQ_CANDIDATO", "ANO_ELEICAO", "NR_TURNO", "CD_CARGO", "DS_CARGO",
            "NM_CANDIDATO", "NM_URNA_CANDIDATO", "NM_SOCIAL_CANDIDATO",
            "SG_UF", "SG_UE", "NM_UE", "SG_PARTIDO", "NR_PARTIDO", "NM_PARTIDO",
            "NR_CANDIDATO", "CD_SITUACAO_CANDIDATURA", "DS_SITUACAO_CANDIDATURA",
            "DT_NASCIMENTO", "DS_GENERO", "DS_COR_RACA", "DS_GRAU_INSTRUCAO",
            "SG_UF_NASCIMENTO", "DS_SIT_TOT_TURNO", "SG_FEDERACAO",
            "NM_COLIGACAO", "DS_COMPOSICAO_COLIGACAO")),

        // Câmara — cadastro de deputados. `cpf` existe na origem e fica de fora:
        // verificado em 30/08/2026 que a coluna vem vazia nas 7.889 linhas, e
        // mesmo que passasse a vir preenchida não teria uso aqui.
        Map.entry("CAMARA:parlamentar", Set.of(
            "uri", "nome", "nomeCivil", "idLegislaturaInicial", "idLegislaturaFinal",
            "siglaSexo", "urlRedeSocial", "urlWebsite", "dataNascimento",
            "dataFalecimento", "ufNascimento", "municipioNascimento")),

        // Histórico de situação. `email` fica de fora: é dado de contato pessoal
        // e não tem uso nenhum na derivação de ausência.
        Map.entry("CAMARA:exercicio_parlamentar", Set.of(
            "id", "dataHora", "situacao", "condicaoEleitoral", "descricaoStatus",
            "idLegislatura", "siglaUf", "siglaPartido", "nomeEleitoral")),

        Map.entry("CAMARA:proposicao", Set.of(
            "id", "uri", "siglaTipo", "numero", "ano", "ementa", "ementaDetalhada",
            "keywords", "dataApresentacao", "uriOrgaoNumerador", "urlInteiroTeor")),

        Map.entry("CAMARA:votacao", Set.of(
            "id", "uri", "data", "dataHoraRegistro", "idOrgao", "siglaOrgao",
            "idEvento", "aprovacao", "votosSim", "votosNao", "votosOutros",
            "descricao", "ultimaAberturaVotacao_descricao",
            "ultimaApresentacaoProposicao_idProposicao")),

        Map.entry("CAMARA:voto", Set.of(
            "idVotacao", "uriVotacao", "dataHoraVoto", "voto",
            "deputado_id", "deputado_nome", "deputado_siglaPartido",
            "deputado_siglaUf", "deputado_idLegislatura")),

        // Senado — a votação já traz a bancada inteira aninhada em `votos`.
        Map.entry("SENADO:votacao", Set.of(
            "codigoSessao", "codigoSessaoVotacao", "dataSessao", "idProcesso",
            "codigoMateria", "identificacao", "sigla", "numero", "ano",
            "dataApresentacao", "ementa", "descricaoVotacao", "resultadoVotacao",
            "votacaoSecreta", "totalVotosSim", "totalVotosNao",
            "totalVotosAbstencao", "votos")),

        Map.entry("SENADO:voto", Set.of(
            "codigoParlamentar", "nomeParlamentar", "siglaPartidoParlamentar",
            "siglaUFParlamentar", "siglaVotoParlamentar",
            "descricaoVotoParlamentar")),

        Map.entry("SENADO:proposicao", Set.of(
            "id", "codigoMateria", "identificacao", "sigla", "numero", "ano",
            "ementa", "autoria", "dataApresentacao", "situacaoAtual",
            "dataSituacaoAtual", "objetivo", "tramitando", "urlDocumento")),

        // Senado — cadastro de parlamentares. A resposta da API aninha estes
        // campos dentro de IdentificacaoParlamentar/DadosBasicosParlamentar, e
        // para quem está em exercício esse bloco traz EmailParlamentar e
        // UrlFotoParlamentar (verificado em 01/09/2026). Como esta allowlist
        // filtra por NOME DE CAMPO no primeiro nível — um campo aninhado
        // permitido seria copiado inteiro, sem descer — o orquestrador do
        // Senado monta um registro RASO com só estes cinco campos antes de
        // chegar aqui; e-mail, telefone e foto nunca são incluídos nele.
        Map.entry("SENADO:parlamentar", Set.of(
            "codigoParlamentar", "nomeParlamentar", "nomeCompletoParlamentar",
            "dataNascimento", "ufParlamentar")),

        // ALESP — nomes conferidos contra os arquivos XML reais em 31/08/2026.
        // Os que estavam declarados antes (SiglaTipoProjeto, NumeroProjeto,
        // AnoProjeto, DataEntradaSistema, IdVotacao, Data) eram FANTASMA: não
        // existem em campo nenhum da fonte. Pior, faltava `TipoVoto` — o
        // código de voto, que é a chave inteira do mapeamento da Alesp. A
        // allowlist o teria descartado em silêncio, e todo voto de comissão
        // cairia em quarentena sem que ninguém entendesse por quê.
        Map.entry("ALESP:proposicao", Set.of(
            "IdDocumento", "IdNatureza", "NroLegislativo", "AnoLegislativo",
            "Ementa", "DtPublicacao", "DtEntradaSistema", "CodOriginalidade")),

        // `Deputado` (o nome) fica: é o que permite conferir o vínculo quando
        // a resolução de identidade erra, e é o único identificador legível de
        // quem votou. IdComissao/IdPauta/IdReuniao são chaves da deliberação.
        Map.entry("ALESP:voto", Set.of(
            "IdDocumento", "IdReuniao", "IdPauta", "IdComissao",
            "IdDeputado", "Deputado", "TipoVoto", "Voto")),

        // Cadastro de deputados. Email, Telefone, PlacaVeiculo, Sala, Andar,
        // Matricula, IdUA e Biografia ficam DE FORA: são contato e localização
        // pessoal, e nenhum tem uso na resolução de identidade. `PlacaVeiculo`
        // é o caso que ilustra o argumento da allowlist — ninguém esperaria
        // placa de carro num cadastro parlamentar, e ela está lá.
        //
        // `Aniversario` entra e é só dia/mês ("26/08"): a Alesp não publica o
        // ANO de nascimento, o que impede o casamento determinístico que
        // Câmara e Senado permitem.
        Map.entry("ALESP:parlamentar", Set.of(
            "IdDeputado", "IdSPL", "NomeParlamentar", "Partido", "Situacao",
            "Aniversario")),

        Map.entry("ALESP:reuniao_comissao", Set.of(
            "IdReuniao", "IdPauta", "IdComissao", "Data", "Situacao",
            "CodSituacao", "NrConvocacao", "NrLegislatura", "TipoConvocacao",
            "Presidente")),

        Map.entry("ALESP:autoria", Set.of(
            "IdDocumento", "IdAutor", "NomeAutor"))
    );

    /** Aninhamentos que também passam pela allowlist do seu próprio recurso. */
    private static final Map<String, String> RECURSO_DE_ARRAY_ANINHADO = Map.of(
        "SENADO:votacao.votos", "SENADO:voto"
    );

    /**
     * @param recurso 'candidatura', 'parlamentar', 'proposicao', 'votacao', 'voto'
     * @throws IllegalArgumentException se o par não tem allowlist declarada —
     *         falhar é melhor que gravar payload não revisado
     */
    public PayloadRedigido redigir(Fonte fonte, String recurso, JsonNode payload) {
        String chave = chave(fonte, recurso);
        Set<String> permitidos = PERMITIDOS.get(chave);
        if (permitidos == null) {
            throw new IllegalArgumentException(
                "sem allowlist declarada para " + chave + "; declare os campos em "
                + RedatorDeCamposSensiveis.class.getSimpleName()
                + " antes de gravar esta origem em staging");
        }
        var removidos = new ArrayList<String>();
        JsonNode limpo = filtrar(chave, payload, permitidos, removidos);
        removidos.sort(String::compareTo);
        return new PayloadRedigido(limpo, List.copyOf(removidos));
    }

    private JsonNode filtrar(String chave, JsonNode no, Set<String> permitidos,
                             List<String> removidos) {
        if (no == null || !no.isObject()) return no;

        ObjectNode saida = ((ObjectNode) no).objectNode();
        for (var entrada : ((ObjectNode) no).properties()) {
            String campo = entrada.getKey();
            if (!permitidos.contains(campo)) {
                removidos.add(campo);
                continue;
            }
            String recursoAninhado = RECURSO_DE_ARRAY_ANINHADO.get(chave + "." + campo);
            if (recursoAninhado != null && entrada.getValue().isArray()) {
                Set<String> permitidosFilho = PERMITIDOS.get(recursoAninhado);
                ArrayNode itens = saida.putArray(campo);
                for (JsonNode item : entrada.getValue()) {
                    itens.add(filtrar(recursoAninhado, item, permitidosFilho, removidos));
                }
            } else {
                saida.set(campo, entrada.getValue());
            }
        }
        return saida;
    }

    private static String chave(Fonte fonte, String recurso) {
        return fonte.name() + ":" + recurso.toLowerCase(Locale.ROOT);
    }
}
