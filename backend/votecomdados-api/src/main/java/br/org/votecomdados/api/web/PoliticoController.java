package br.org.votecomdados.api.web;

import br.org.votecomdados.api.servico.ConsultaPoliticos;
import br.org.votecomdados.core.dominio.Enums.Cargo;
import br.org.votecomdados.core.dominio.Modelo.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/politicos")
@Validated
class PoliticoController {

    private final ConsultaPoliticos consulta;

    PoliticoController(ConsultaPoliticos consulta) {
        this.consulta = consulta;
    }

    /**
     * Limites validados no servidor, não só documentados: a busca textual é o
     * endpoint mais caro de servir e o mais barato de atacar (ver
     * ARQUITETURA.md § 10).
     */
    @GetMapping
    Pagina<PoliticoResumo> listar(
        @RequestParam(required = false) @Size(max = 100) String q,
        @RequestParam(required = false) Cargo cargo,
        @RequestParam(required = false) @Size(min = 2, max = 2) String uf,
        @RequestParam(required = false, defaultValue = "false") boolean comAtuacao,
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return consulta.buscar(q, cargo, uf, comAtuacao, page, pageSize);
    }

    @GetMapping("/{id}")
    PoliticoPerfil perfil(@PathVariable UUID id) {
        return consulta.perfil(id);
    }

    @GetMapping("/{id}/proposicoes")
    Pagina<Proposicao> proposicoes(
        @PathVariable UUID id,
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return consulta.proposicoes(id, page, pageSize);
    }

    @GetMapping("/{id}/votacoes")
    Pagina<VotacaoDoPolitico> votacoes(
        @PathVariable UUID id,
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return consulta.votacoes(id, page, pageSize);
    }
}
