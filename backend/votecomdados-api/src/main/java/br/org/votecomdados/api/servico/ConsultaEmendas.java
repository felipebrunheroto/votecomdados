package br.org.votecomdados.api.servico;

import br.org.votecomdados.api.repositorio.EmendaRepositorio;
import br.org.votecomdados.core.dominio.Enums.LocalidadeEmenda;
import br.org.votecomdados.core.dominio.Modelo.Emenda;
import br.org.votecomdados.core.dominio.Modelo.EmendasDoMunicipio;
import br.org.votecomdados.core.dominio.Modelo.FatiaDeLocalidade;
import br.org.votecomdados.core.dominio.Modelo.PeriodoCoberto;
import br.org.votecomdados.core.dominio.Modelo.ResumoDeEmendas;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ConsultaEmendas {

    private final EmendaRepositorio repositorio;

    ConsultaEmendas(EmendaRepositorio repositorio) {
        this.repositorio = repositorio;
    }

    public record PaginaDeEmendas(
        List<Emenda> data, ResumoDeEmendas resumo, Paginacao pagination) {}

    public record Paginacao(int page, int pageSize, int total) {}

    /**
     * Todas as cidades com emenda, com dados completos.
     *
     * <p>O resumo nacional é calculado UMA vez e compartilhado: consultá-lo
     * por cidade faria 1.596 agregações idênticas sobre a tabela inteira.
     */
    public List<EmendasDoMunicipio> todosOsMunicipios() {
        var nacional = repositorio.resumoNacional();
        var porCidade = repositorio.emendasPorMunicipio();

        return porCidade.entrySet().stream().map(e -> {
            String[] chave = e.getKey().split("/", 2);
            List<Emenda> lista = e.getValue();
            return new EmendasDoMunicipio(chave[1], chave[0], true,
                (int) lista.stream()
                    .map(x -> x.politicoId() != null ? x.politicoId().toString() : x.autorNome())
                    .distinct().count(),
                resumir(lista), nacional, lista);
        }).toList();
    }

    /** Cidades com emenda identificada, para o pré-render do frontend. */
    public List<EmendaRepositorio.Municipio> municipiosComEmenda() {
        return repositorio.municipiosComEmenda();
    }

    public PaginaDeEmendas doPolitico(UUID politicoId, int page, int pageSize) {
        int deslocamento = (page - 1) * pageSize;
        return new PaginaDeEmendas(
            repositorio.doPolitico(politicoId, pageSize, deslocamento),
            repositorio.resumoDoPolitico(politicoId),
            new Paginacao(page, pageSize, repositorio.contarDoPolitico(politicoId)));
    }

    /**
     * O que uma cidade recebeu.
     *
     * <p><b>Cidade sem registro não é erro — é o caso comum.</b> Só 474 dos
     * 5.570 municípios brasileiros aparecem nas emendas de 2025, porque 88,5%
     * do dinheiro é registrado sem discriminar município. Responder 404 aqui
     * seria dizer "esta cidade não existe" quando o certo é "esta fonte não
     * diz nada sobre ela"; e uma resposta vazia sem marca nenhuma seria lida
     * como "não recebeu nada", que é pior ainda porque parece um fato.
     *
     * <p>Daí {@code temRegistro}: a interface precisa distinguir os dois
     * zeros. Quarenta e três cidades receberam dinheiro e apareceriam como
     * zero se só {@code pago} fosse olhado; quarenta outras realmente não
     * receberam.
     */
    public EmendasDoMunicipio doMunicipio(String uf, String municipio) {
        List<Emenda> emendas = repositorio.doMunicipio(uf, municipio);

        return new EmendasDoMunicipio(
            municipio, uf.toUpperCase(),
            !emendas.isEmpty(),
            emendas.isEmpty() ? 0 : repositorio.parlamentaresDoMunicipio(uf, municipio),
            resumir(emendas),
            repositorio.resumoNacional(),
            emendas);
    }

    /**
     * Soma sobre a lista já carregada, em vez de nova consulta.
     *
     * <p>Uma cidade tem poucas emendas — a mais citada de 2025 tem 17 —, e
     * somar aqui evita um SQL agregado que precisaria repetir a comparação
     * sem acento do nome e correria o risco de divergir dela.
     */
    private ResumoDeEmendas resumir(List<Emenda> emendas) {
        BigDecimal empenhado = soma(emendas, Emenda::empenhado);
        BigDecimal pago = soma(emendas, Emenda::pago);
        BigDecimal resto = soma(emendas, Emenda::restoPago);

        List<FatiaDeLocalidade> fatias = emendas.isEmpty() ? List.of()
            : List.of(new FatiaDeLocalidade(LocalidadeEmenda.MUNICIPIO,
                emendas.size(), pago.add(resto)));

        int[] p = repositorio.periodoCoberto();
        return new ResumoDeEmendas(emendas.size(), empenhado, pago, resto,
            pago.add(resto), fatias,
            p == null ? null : new PeriodoCoberto(p[0], p[1]),
            // Do acervo, nao deste municipio: serve para a tela dizer quantas
            // cidades estao na mesma situacao.
            repositorio.resumoNacional().municipiosComRegistro());
    }

    /** Ausência conta como zero na soma, sem apagar a linha. */
    private static BigDecimal soma(List<Emenda> emendas,
                                   java.util.function.Function<Emenda, BigDecimal> campo) {
        return emendas.stream()
            .map(e -> campo.apply(e) == null ? BigDecimal.ZERO : campo.apply(e))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
