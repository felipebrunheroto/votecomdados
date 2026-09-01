package br.org.votecomdados.api.web;

import br.org.votecomdados.api.servico.ConsultaDetalhes;
import br.org.votecomdados.core.dominio.Modelo.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class DetalheController {

    private final ConsultaDetalhes consulta;

    DetalheController(ConsultaDetalhes consulta) {
        this.consulta = consulta;
    }

    @GetMapping("/proposicoes/{id}")
    ProposicaoDetalhe proposicao(@PathVariable long id) {
        return consulta.proposicao(id);
    }

    @GetMapping("/votacoes/{id}")
    VotacaoDetalhe votacao(@PathVariable long id) {
        return consulta.votacao(id);
    }

    /**
     * Sem paginação, de propósito — só alimenta {@code generateStaticParams}
     * no build do frontend (achado B1). Não é a rota de navegação; essa é
     * sempre por político (`/politicos/{id}/proposicoes`).
     */
    @GetMapping("/proposicoes")
    ListaDeIds idsDeProposicoes() {
        return consulta.todosOsIdsDeProposicoes();
    }

    @GetMapping("/votacoes")
    ListaDeIds idsDeVotacoes() {
        return consulta.todosOsIdsDeVotacoes();
    }

    @GetMapping("/meta/status")
    StatusFontes status() {
        return consulta.statusDasFontes();
    }
}
