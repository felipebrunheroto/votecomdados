package br.org.votecomdados.core.dominio;

/**
 * Enums do domínio, espelhando os tipos de db/schema.sql.
 *
 * Os nomes coincidem com os valores gravados no banco e com os do contrato em
 * docs/API.md — a serialização JSON usa o próprio nome da constante, então
 * renomear qualquer um destes quebra o frontend.
 */
public final class Enums {

    private Enums() {}

    public enum Esfera { FEDERAL, ESTADUAL, MUNICIPAL }

    public enum Cargo {
        PRESIDENTE, VICE_PRESIDENTE,
        GOVERNADOR, VICE_GOVERNADOR,
        SENADOR, PRIMEIRO_SUPLENTE, SEGUNDO_SUPLENTE,
        DEPUTADO_FEDERAL, DEPUTADO_ESTADUAL, DEPUTADO_DISTRITAL,
        PREFEITO, VICE_PREFEITO, VEREADOR
    }

    public enum CasaLegislativa { CAMARA, SENADO, ALESP }

    /**
     * Voto de comissão não tem o mesmo peso de deliberação em plenário.
     * Misturá-los numa lista única inflaria a atuação aparente de quem tem
     * dados de comissão publicados.
     */
    public enum AmbitoVotacao { PLENARIO, COMISSAO }

    public enum TipoVotacao { NOMINAL, SIMBOLICA }

    /**
     * Interpretação nossa do rótulo da fonte. O texto original é sempre
     * preservado em VotoNominal.votoOrigem — o enum é leitura, a string é fato.
     *
     * AUSENTE e LICENCIADO são a exceção: nenhuma fonte os publica. Saem do
     * cruzamento entre a votação e quem estava em exercício na data, e chegam
     * com origemRegistro = DERIVADO e votoOrigem nulo.
     */
    public enum TipoVoto {
        SIM, NAO, ABSTENCAO,
        /**
         * Voto em branco. A Alesp o conta SEPARADO da abstenção no placar dela
         * (códigos 'B' e 'A'), e colapsar os dois apagaria uma distinção que a
         * fonte faz.
         */
        BRANCO,
        AUSENTE, LICENCIADO,
        /** Ausência por trabalho da Casa: missão oficial, atividade parlamentar. */
        AUSENCIA_JUSTIFICADA,
        /** Estava na sessão e não registrou voto. Não é falta. */
        PRESENTE_NAO_VOTOU,
        /** Votação secreta: a Casa registra quem participou, não como votou. */
        SECRETO,
        OBSTRUCAO,
        /**
         * Votou apresentando parecer escrito divergente do relator (código 'S'
         * da Alesp). A fonte NÃO diz se o divergente é favorável ou contrário
         * ao projeto — a amostra real tem "Com o Voto em Separado contrário" e
         * "favorável" —, então inferir a direção seria inventar posição.
         */
        VOTO_EM_SEPARADO,
        /** Presidente que não vota. Art. 17 na Câmara, art. 51 do RISF no Senado. */
        ART_17
    }

    /**
     * FONTE: a Casa publicou a linha. DERIVADO: nós a calculamos.
     *
     * A UI é obrigada a marcar a diferença — apresentar cálculo nosso como
     * registro oficial seria o mesmo erro que votoOrigem existe para impedir.
     */
    public enum OrigemRegistro { FONTE, DERIVADO }

    /**
     * {@link #NAO_INFORMADO} não é ausência de dado nosso: é o que a fonte
     * declara. O TSE usa o sentinela {@code #NE} enquanto o registro está sendo
     * julgado — em 31/08/2026 era o caso das 20.809 candidaturas de 2026.
     * Traduzi-lo para {@link #APTO} seria afirmar em nome do TSE.
     */
    public enum StatusCandidatura {
        NAO_INFORMADO, DEFERIDO, INDEFERIDO, CASSADO, RENUNCIA, APTO, INAPTO
    }

    public enum Fonte { CAMARA, SENADO, TSE, ALESP }

    /**
     * COORTE define QUEM interessa (candidatos de 2026) e é pré-requisito dos
     * demais; BACKFILL busca o histórico dessas pessoas; INCREMENTAL só o que
     * mudou desde o último watermark.
     */
    public enum TipoJob { COORTE, BACKFILL, INCREMENTAL }

    /**
     * Como um vínculo entre a pessoa e o cadastro de uma Casa foi estabelecido.
     *
     * <p>A distinção é de auditoria, não de implementação: um vínculo
     * {@link #FUZZY} é afirmação nossa com margem de erro, e precisa poder ser
     * separado dos determinísticos por quem for conferir o cruzamento — inclusive
     * de fora, pelos dados abertos.
     */
    public enum MetodoResolucao { DETERMINISTICO, FUZZY }

    /**
     * Por que um registro foi para quarentena em vez de ser descartado.
     *
     * <p>{@link #FORA_DA_COORTE} é o único ESPERADO: parlamentar que não é
     * candidato em 2026 não é defeito. Ele é contado e nunca alertado — sem
     * essa distinção, a maioria das ~398 linhas de cada votação nominal cairia
     * em {@link #POLITICO_NAO_RESOLVIDO} e a métrica, cujo valor esperado é
     * zero, nasceria com dezenas de milhares de linhas.
     */
    public enum MotivoRejeicao {
        POLITICO_NAO_RESOLVIDO,
        FORA_DA_COORTE,
        VOTACAO_DESCONHECIDA,
        PROPOSICAO_DESCONHECIDA,
        VALOR_VOTO_NAO_MAPEADO,
        SITUACAO_NAO_MAPEADA,
        PAYLOAD_INVALIDO;

        /** Quarentena esperada não dispara alerta; qualquer outra, sim. */
        public boolean exigeAlerta() {
            return this != FORA_DA_COORTE;
        }
    }

    public enum StatusExecucao { EM_ANDAMENTO, CONCLUIDA, FALHA }

    /**
     * As três situações de cobertura. "A fonte não publica" e "ainda não
     * cobrimos" geram mensagens diferentes ao eleitor, e confundi-las seria
     * desonesto nos dois sentidos.
     */
    public enum StatusCobertura {
        DISPONIVEL, NAO_PUBLICADO_PELA_FONTE, FORA_DO_ESCOPO_MVP
    }
}
