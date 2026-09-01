package br.org.votecomdados.ingestion.coorte;

import br.org.votecomdados.core.dominio.Enums.Fonte;
import br.org.votecomdados.core.dominio.Enums.MotivoRejeicao;
import br.org.votecomdados.ingestion.execucao.Execucao;
import br.org.votecomdados.ingestion.staging.RepositorioDePayloadBruto;
import br.org.votecomdados.ingestion.staging.ServicoDeQuarentena;
import java.util.Iterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Monta a coorte: quem é candidato em 2026, e toda a trajetória eleitoral dessas
 * pessoas.
 *
 * <p><b>É o job que define o escopo do sistema</b>, e por isso é pré-requisito
 * de todos os outros. O pipeline é invertido em relação ao intuitivo: primeiro
 * se descobre <i>quem</i> interessa (TSE), depois se busca <i>o que</i> essas
 * pessoas fizeram (Câmara, Senado, Alesp). Carregar tudo e filtrar na exibição
 * seria mais simples de escrever e criaria uma base de dados pessoais que a
 * arquitetura declara não existir.
 *
 * <h2>Ordem interna, e por que ela é essa</h2>
 *
 * <ol>
 *   <li>Anos <b>anteriores</b> primeiro, 2026 por último. A trajetória se
 *       costura pelo {@code cpf_hmac}, e todos os anos precisam estar
 *       carregados antes do expurgo.</li>
 *   <li><b>Poda</b> depois de tudo carregado: podar no meio removeria quem
 *       ainda não teve a candidatura de 2026 lida.</li>
 *   <li><b>Expurgo do HMAC</b> por último, quando ele já cumpriu seu papel.</li>
 * </ol>
 */
@Component
public class JobDeCoorte {

    private static final Logger log = LoggerFactory.getLogger(JobDeCoorte.class);

    /** A eleição que define quem existe na base. */
    public static final int ANO_DA_COORTE = 2026;

    private final LeitorDeCandidaturasTse leitor;
    private final RepositorioDeCoorte repositorio;
    private final RepositorioDePayloadBruto staging;
    private final ServicoDeQuarentena quarentena;

    JobDeCoorte(LeitorDeCandidaturasTse leitor, RepositorioDeCoorte repositorio,
                RepositorioDePayloadBruto staging, ServicoDeQuarentena quarentena) {
        this.leitor = leitor;
        this.repositorio = repositorio;
        this.staging = staging;
        this.quarentena = quarentena;
    }

    /**
     * Carrega um lote de candidaturas de um ano.
     *
     * <p>Recebe um iterador em vez de uma coleção de propósito: o arquivo do TSE
     * tem centenas de milhares de linhas por ano, e materializá-lo em memória
     * seria desnecessário — cada linha é independente.
     */
    public Resultado carregarAno(Execucao execucao, Iterator<JsonNode> linhas) {
        int processados = 0;
        int rejeitados = 0;

        while (linhas.hasNext()) {
            JsonNode linha = linhas.next();
            staging.gravar(execucao, "candidatura", idExterno(linha), linha);

            var candidatura = leitor.ler(linha);
            if (candidatura.isEmpty()) {
                // Cargo desconhecido: não se adivinha. Vai para quarentena
                // visível, com payload redigido, em vez de sumir.
                quarentena.rejeitar(execucao, "candidatura", idExterno(linha),
                                    MotivoRejeicao.PAYLOAD_INVALIDO,
                                    "cargo nao reconhecido: CD_CARGO="
                                    + textoDe(linha, "CD_CARGO"), linha);
                rejeitados++;
                continue;
            }

            var c = candidatura.get();
            var politicoId = repositorio.encontrarOuCriar(c);
            repositorio.gravarCandidatura(politicoId, c);
            processados++;
        }

        return new Resultado(processados, rejeitados);
    }

    /**
     * Encerra a coorte: poda quem saiu e apaga o CPF.
     *
     * <p>Chamado uma vez, depois de TODOS os anos carregados. Chamar no meio
     * removeria pessoas cuja candidatura de 2026 ainda não foi lida, e apagaria
     * o HMAC antes de ele ter costurado a trajetória.
     */
    public void encerrar() {
        int podados = repositorio.podarForaDaCoorte(ANO_DA_COORTE);
        if (podados > 0) {
            log.info("poda: {} pessoa(s) deixaram de ser candidatas em {} e foram "
                     + "removidas com todo o historico", podados, ANO_DA_COORTE);
        }

        int expurgados = repositorio.expurgarCpfHmac();
        log.info("expurgo: cpf_hmac zerado em {} registro(s); o vinculo agora vive "
                 + "apenas em identificador_externo", expurgados);
    }

    private static String idExterno(JsonNode linha) {
        return textoDe(linha, "SQ_CANDIDATO");
    }

    private static String textoDe(JsonNode linha, String campo) {
        JsonNode v = linha.get(campo);
        return v == null || v.isNull() ? null : v.asString();
    }

    public record Resultado(int processados, int rejeitados) {}
}
