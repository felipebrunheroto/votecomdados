package br.org.votecomdados.api.servico;

import br.org.votecomdados.api.repositorio.*;
import br.org.votecomdados.api.web.Erros.NaoEncontrado;
import br.org.votecomdados.core.dominio.Enums.Cargo;
import br.org.votecomdados.core.dominio.Modelo.*;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ConsultaPoliticos {

    private final PoliticoRepositorio politicos;
    private final ProposicaoRepositorio proposicoes;
    private final VotacaoRepositorio votacoes;

    ConsultaPoliticos(PoliticoRepositorio politicos, ProposicaoRepositorio proposicoes,
                      VotacaoRepositorio votacoes) {
        this.politicos = politicos;
        this.proposicoes = proposicoes;
        this.votacoes = votacoes;
    }

    public Pagina<PoliticoResumo> buscar(String q, Cargo cargo, String uf,
                                         boolean comAtuacao, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        var itens = politicos.buscar(q, cargo, uf, comAtuacao, pageSize, offset);
        long total = politicos.contar(q, cargo, uf, comAtuacao);
        return new Pagina<>(itens, new Paginacao(page, pageSize, total));
    }

    /**
     * 404 também para quem não é candidato em 2026: essas pessoas não têm
     * registro pessoal na base, então "não existe" é a resposta correta.
     */
    public PoliticoPerfil perfil(UUID id) {
        return politicos.perfil(id)
            .orElseThrow(() -> new NaoEncontrado("Candidato não encontrado: " + id));
    }

    public Pagina<Proposicao> proposicoes(UUID id, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        return new Pagina<>(
            proposicoes.doPolitico(id, pageSize, offset),
            new Paginacao(page, pageSize, proposicoes.contarDoPolitico(id)));
    }

    public Pagina<VotacaoDoPolitico> votacoes(UUID id, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        return new Pagina<>(
            votacoes.doPolitico(id, pageSize, offset),
            new Paginacao(page, pageSize, votacoes.contarDoPolitico(id)));
    }
}
