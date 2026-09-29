package br.org.votecomdados.api.repositorio;

import br.org.votecomdados.core.dominio.Modelo.Emenda;
import br.org.votecomdados.core.dominio.Modelo.ResumoDeEmendas;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Repositório de mentira que conta quantas vezes o resumo nacional foi pedido.
 *
 * <p>Mora neste pacote porque o construtor de {@link EmendaRepositorio} é
 * package-private, como todos os beans do projeto. Ampliar a visibilidade de
 * produção para acomodar um teste seria o caminho errado; uma classe de teste
 * pública aqui não custa nada.
 */
public class RepositorioDeEmendasContador extends EmendaRepositorio {

    public final AtomicInteger chamadasAoNacional = new AtomicInteger();

    private final Map<String, List<Emenda>> porCidade;
    private final ResumoDeEmendas nacional;

    public RepositorioDeEmendasContador(Map<String, List<Emenda>> porCidade,
                                        ResumoDeEmendas nacional) {
        super(null);
        this.porCidade = porCidade;
        this.nacional = nacional;
    }

    @Override
    public ResumoDeEmendas resumoNacional() {
        chamadasAoNacional.incrementAndGet();
        return nacional;
    }

    @Override
    public Map<String, List<Emenda>> emendasPorMunicipio() {
        return porCidade;
    }
}
