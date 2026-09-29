package br.org.votecomdados.core.dominio;

import br.org.votecomdados.core.dominio.Enums.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Modelo de leitura devolvido pela API.
 *
 * Os nomes dos campos são o contrato de docs/API.md — a serialização usa
 * diretamente estes records, então renomear um campo quebra o frontend.
 */
public final class Modelo {

    private Modelo() {}

    public record Candidatura(
        int anoEleicao,
        Cargo cargo,
        Esfera esfera,
        String uf,
        String municipio,
        String partidoSigla,
        StatusCandidatura status,
        Boolean eleito
    ) {}

    public record Cobertura(
        Esfera esfera,
        String uf,
        /** `null` quando a regra não é de uma Casa (trajetória eleitoral, TSE). */
        CasaLegislativa casa,
        String recurso,
        StatusCobertura status,
        LocalDate disponivelDesde,
        String observacao
    ) {}

    public record PoliticoResumo(
        UUID id,
        String nomeCivil,
        String nomeUrna,
        Cargo cargo2026,
        String uf,
        String partidoSigla,
        StatusCandidatura statusCandidatura,
        boolean possuiAtuacaoLegislativa
    ) {}

    public record PoliticoPerfil(
        UUID id,
        String nomeCivil,
        String nomeUrna,
        boolean possuiAtuacaoLegislativa,
        List<Candidatura> trajetoria,
        List<Cobertura> cobertura
    ) {}

    public record Proposicao(
        long id,
        CasaLegislativa casa,
        Esfera esfera,
        String siglaTipo,
        Integer numero,
        // Nulo quando a peça não tem ano na designação oficial: parecer,
        // emenda e substitutivo são numerados dentro da tramitação de outra
        // matéria. A fonte publica 0 nesses casos; ver V14.
        Integer ano,
        String ementa,
        List<String> temas,
        LocalDate dataApresentacao,
        String situacaoAtual,
        String urlInteiroTeor,
        String urlTramitacao
    ) {}

    /** `politicoId` nulo = coautor fora da coorte: aparece pelo nome, sem link. */
    public record AutorProposicao(UUID politicoId, String nome, boolean autorPrincipal) {}

    public record ProposicaoDetalhe(
        long id,
        CasaLegislativa casa,
        Esfera esfera,
        String siglaTipo,
        Integer numero,
        // Nulo quando a peça não tem ano na designação oficial: parecer,
        // emenda e substitutivo são numerados dentro da tramitação de outra
        // matéria. A fonte publica 0 nesses casos; ver V14.
        Integer ano,
        String ementa,
        List<String> temas,
        LocalDate dataApresentacao,
        String situacaoAtual,
        String urlInteiroTeor,
        String urlTramitacao,
        List<AutorProposicao> autores
    ) {}

    /**
     * `voto` nulo apenas em votação simbólica. `votoOrigem` carrega o rótulo
     * literal da fonte e é obrigatório em votação nominal — a UI exibe os dois.
     */
    public record VotacaoDoPolitico(
        long votacaoId,
        Instant dataVotacao,
        String descricao,
        CasaLegislativa casa,
        Esfera esfera,
        AmbitoVotacao ambito,
        List<String> temas,
        TipoVotacao tipo,
        /** Secreta é NOMINAL: registra quem participou, não como votou. */
        boolean secreta,
        TipoVoto voto,
        String votoOrigem,
        OrigemRegistro origemRegistro,
        String notaMetodologica,
        String observacao,
        Boolean aprovada,
        String urlFonte
    ) {}

    /** `outros` agrupa ausências, obstruções e Art. 17 — não são posição sobre o mérito. */
    public record Placar(int sim, int nao, int abstencao, int outros) {}

    public record VotacaoDetalhe(
        long id,
        String descricao,
        CasaLegislativa casa,
        Esfera esfera,
        AmbitoVotacao ambito,
        TipoVotacao tipo,
        /** Secreta é NOMINAL: há registro de quem participou, não de como votou. */
        boolean secreta,
        Instant dataVotacao,
        Placar placar,
        Boolean aprovada,
        Long proposicaoId,
        String observacao,
        String urlFonte
    ) {}

    public record StatusFonte(Fonte fonte, Instant ultimaAtualizacao, StatusExecucao status) {}

    public record StatusFontes(List<StatusFonte> fontes) {}

    public record Paginacao(int page, int pageSize, long total) {}

    public record Pagina<T>(List<T> data, Paginacao pagination) {}

    /**
     * Todos os ids de um recurso, sem paginação — de propósito. Usado só por
     * {@code generateStaticParams} no build do frontend (achado B1,
     * 01/09/2026): a finalidade é gerar página estática para cada matéria e
     * votação, não navegar uma listagem. Paginar aqui sugeriria uma feature
     * de "ver todas as matérias" que o produto não decidiu oferecer.
     */
    public record ListaDeIds(List<Long> ids) {}

    // --- Emendas parlamentares ------------------------------------------

    /**
     * Uma emenda, como a API a expõe.
     *
     * <p><b>{@code pago} e {@code restoPago} andam sempre juntos, e
     * {@code desembolso} é a soma.</b> Não é redundância: em 2025 saíram
     * R$ 32,5 bi por {@code pago} e R$ 6,1 bi por restos a pagar — 15,8% do
     * total. E 43 das 474 cidades com emenda identificada têm {@code pago = 0}
     * com restos pagos maiores que zero. Santos é uma delas: apareceria com
     * "R$ 0,00 recebido" tendo recebido R$ 600 mil.
     *
     * <p>Uma interface que mostre só {@code pago} mente sobre essas cidades.
     * O campo somado vem pronto do servidor justamente para que a escolha
     * errada não seja a mais fácil de fazer.
     *
     * <p>{@code autorOrigemNome} é preenchido quando a emenda foi transferida
     * de outro parlamentar — 1,7% das linhas de 2025. Exibir só quem a detém
     * hoje esconde metade da história.
     */
    public record Emenda(
        String codigo,
        int ano,
        String tipo,
        String autorNome,
        UUID politicoId,
        String autorOrigemNome,
        String municipio,
        String uf,
        String localidadeBruta,
        LocalidadeEmenda localidadeTipo,
        BigDecimal empenhado,
        BigDecimal liquidado,
        BigDecimal pago,
        BigDecimal restoPago,
        BigDecimal desembolso
    ) {}

    /**
     * Quanto do dinheiro de um recorte tem município identificado.
     *
     * <p>Existe para a interface poder <b>declarar a lacuna</b> sem recalcular
     * nada. Em 2025, por valor pago no país inteiro: MÚLTIPLO 88,5%, ESTADO
     * 7,5%, MUNICÍPIO 3,4%, NACIONAL 0,6%. Uma página que some tudo sem
     * separar afirma uma cobertura que não tem.
     */
    public record ResumoDeEmendas(
        int linhas,
        BigDecimal empenhado,
        BigDecimal pago,
        BigDecimal restoPago,
        BigDecimal desembolso,
        List<FatiaDeLocalidade> porLocalidade,
        /**
         * Anos que a base cobre. É da NOSSA cobertura, não do recorte
         * consultado — derivá-lo das linhas exibidas diria "2025" para quem só
         * teve emenda naquele ano, sugerindo que é só disso que dispomos.
         * Nulo enquanto não houver nenhuma emenda carregada.
         */
        PeriodoCoberto periodo,
        /**
         * Quantos municípios distintos têm ao menos uma emenda registrada.
         *
         * <p>É a terceira vez que um número desses vira afirmação que apodrece:
         * "474 de 5.570" foi medido com um ano carregado, e virou 1.596 quando
         * quatro entraram. A tela dizia ao leitor "nove em cada dez municípios
         * estão nesta situação" quando já eram sete. Vindo daqui, o texto fica
         * certo sozinho a cada ano que entra.
         */
        int municipiosComRegistro
    ) {}

    public record PeriodoCoberto(int anoInicio, int anoFim) {}

    public record FatiaDeLocalidade(
        LocalidadeEmenda localidade,
        int linhas,
        BigDecimal desembolso
    ) {}

    /**
     * O que uma cidade recebeu.
     *
     * <p>{@code temRegistro} distingue os dois zeros, e a distinção não é
     * sutil: <b>43 cidades receberam dinheiro e apareceriam como zero</b> se a
     * interface olhasse só {@code pago}, enquanto 40 outras realmente não
     * receberam nada. Sem este campo, "R$ 0,00" significa as duas coisas.
     */
    public record EmendasDoMunicipio(
        String municipio,
        String uf,
        boolean temRegistro,
        int parlamentares,
        ResumoDeEmendas resumo,
        /**
         * O acervo inteiro, para a tela declarar a lacuna com número vivo.
         *
         * <p>O resumo do município não serve para isso: ali todas as linhas
         * são de município por construção, então a fração local é sempre
         * 100% e não diz nada sobre o que ficou de fora.
         */
        ResumoDeEmendas nacional,
        List<Emenda> emendas
    ) {}
}
