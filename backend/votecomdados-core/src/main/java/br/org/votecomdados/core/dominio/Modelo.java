package br.org.votecomdados.core.dominio;

import br.org.votecomdados.core.dominio.Enums.*;
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
        int ano,
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
        int ano,
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
}
