package br.org.votecomdados.api.servico;

import br.org.votecomdados.api.repositorio.*;
import br.org.votecomdados.api.web.Erros.NaoEncontrado;
import br.org.votecomdados.core.dominio.Modelo.*;
import org.springframework.stereotype.Service;

@Service
public class ConsultaDetalhes {

    private final ProposicaoRepositorio proposicoes;
    private final VotacaoRepositorio votacoes;
    private final MetaRepositorio meta;

    ConsultaDetalhes(ProposicaoRepositorio proposicoes, VotacaoRepositorio votacoes,
                     MetaRepositorio meta) {
        this.proposicoes = proposicoes;
        this.votacoes = votacoes;
        this.meta = meta;
    }

    public ProposicaoDetalhe proposicao(long id) {
        return proposicoes.porId(id)
            .orElseThrow(() -> new NaoEncontrado("Proposição não encontrada: " + id));
    }

    public VotacaoDetalhe votacao(long id) {
        return votacoes.porId(id)
            .orElseThrow(() -> new NaoEncontrado("Votação não encontrada: " + id));
    }

    public StatusFontes statusDasFontes() {
        return new StatusFontes(meta.statusDasFontes());
    }

    /**
     * Achado B1 (01/09/2026): o frontend chamava {@code GET /proposicoes} e
     * {@code GET /votacoes} para alimentar {@code generateStaticParams}, e a
     * rota nunca existiu — 500 disfarçado de "erro interno" pelo handler
     * genérico. Os repositórios já tinham {@code todosOsIds()} pronto, sem
     * nenhum controller chamando.
     */
    public ListaDeIds todosOsIdsDeProposicoes() {
        return new ListaDeIds(proposicoes.todosOsIds());
    }

    public ListaDeIds todosOsIdsDeVotacoes() {
        return new ListaDeIds(votacoes.todosOsIds());
    }
}
