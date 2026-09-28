package br.org.votecomdados.api.web;

import br.org.votecomdados.api.servico.ConsultaDetalhes;
import br.org.votecomdados.api.servico.ConsultaEmendas;
import br.org.votecomdados.core.dominio.Modelo.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class DetalheController {

    private final ConsultaDetalhes consulta;
    private final ConsultaEmendas emendas;

    DetalheController(ConsultaDetalhes consulta, ConsultaEmendas emendas) {
        this.consulta = consulta;
        this.emendas = emendas;
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

    /**
     * O que uma cidade recebeu em emendas.
     *
     * <p>Sem paginação: a cidade mais citada de 2025 tem 17 emendas.
     *
     * <p>Cidade sem registro responde <b>200 com {@code temRegistro: false}</b>,
     * não 404. Um 404 diria "esta cidade não existe"; o certo é "esta fonte não
     * diz nada sobre ela" — e é o caso de 9 em cada 10 municípios brasileiros.
     */
    /**
     * Cidades com ao menos uma emenda identificada.
     *
     * <p>Sem paginação — alimenta {@code generateStaticParams}, como
     * {@code /proposicoes}. Não é rota de navegação.
     */
    @GetMapping("/emendas/municipios")
    java.util.List<br.org.votecomdados.api.repositorio.EmendaRepositorio.Municipio>
    municipiosComEmenda() {
        return emendas.municipiosComEmenda();
    }

    @GetMapping("/emendas/municipios/{uf}/{municipio}")
    EmendasDoMunicipio emendasDoMunicipio(@PathVariable String uf,
                                          @PathVariable String municipio) {
        return emendas.doMunicipio(uf, municipio);
    }

    @GetMapping("/meta/status")
    StatusFontes status() {
        return consulta.statusDasFontes();
    }
}
