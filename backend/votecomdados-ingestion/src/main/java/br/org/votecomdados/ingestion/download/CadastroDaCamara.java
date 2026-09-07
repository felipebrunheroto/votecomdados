package br.org.votecomdados.ingestion.download;

import br.org.votecomdados.ingestion.csv.LeitorDeCsv;
import br.org.votecomdados.ingestion.execucao.Execucao;
import br.org.votecomdados.ingestion.identidade.JobDeCadastroDeParlamentares;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Baixa {@code deputados.csv} e resolve a identidade de cada deputado.
 *
 * <h2>Por que isto passou a existir</h2>
 *
 * O {@link JobDeCadastroDeParlamentares} era chamado pelo Senado e pela Alesp,
 * e por mais ninguém: a Câmara nunca teve etapa de cadastro. O endereço do
 * arquivo ({@code ArquivosDaCamara.deputados()}) estava escrito e nunca era
 * chamado — nem em código, nem em teste.
 *
 * <p>A consequência só aparecia no fim: o {@code INSERT INTO proposicao} de
 * {@link br.org.votecomdados.ingestion.massa.JobDeBackfillCamara} exige que a
 * matéria tenha ao menos um autor presente em {@code identificador_externo}
 * com {@code sistema = 'CAMARA'}. Com a tabela vazia para a Câmara, o filtro
 * não achava ninguém e a carga inteira virava zero — em 07/09/2026, 61.534
 * proposições lidas, 97.122 autorias, 116.400 votos, e <b>nenhuma matéria e
 * nenhum voto gravados</b>. As votações entravam, porque votação não
 * referencia político.
 *
 * <h2>Antes da coorte, não adianta</h2>
 *
 * A resolução casa o deputado com alguém que já esteja em {@code politico}. Um
 * cadastro rodado antes do {@code COORTE} classificaria todo mundo como
 * {@code FORA_DA_COORTE} — sem erro, e sem vínculo nenhum. A ordem que vale é
 * a da {@code docs/BACKEND.md}: coorte, cadastro, depois matéria e voto.
 */
@Component
public class CadastroDaCamara {

    private static final Logger log = LoggerFactory.getLogger(CadastroDaCamara.class);

    private final BaixadorDeArquivos baixador;
    private final JobDeCadastroDeParlamentares cadastro;
    private final LeitorDeCsv csv;

    CadastroDaCamara(BaixadorDeArquivos baixador, JobDeCadastroDeParlamentares cadastro,
                     LeitorDeCsv csv) {
        this.baixador = baixador;
        this.cadastro = cadastro;
        this.csv = csv;
    }

    /**
     * @param desde watermark da execução anterior; {@code null} baixa sempre —
     *              é o que o backfill usa, já que o histórico nunca foi lido
     * @return {@code true} se a fonte entregou cadastro novo. {@code false}
     *         não é falha: significa 304, e o vínculo já resolvido segue
     *         valendo — resolver identidade é idempotente, mas refazê-lo à toa
     *         custa uma varredura de 7.889 linhas contra o banco todo dia.
     */
    public boolean atualizar(Execucao execucao, Path trabalho, Instant desde, URI endereco) {
        var baixado = baixador.baixarSeMudou(endereco, trabalho.resolve("deputados.csv"), desde);
        if (baixado.isEmpty()) {
            log.info("cadastro da camara nao mudou desde {}: vinculo vigente segue valendo", desde);
            return false;
        }

        var r = cadastro.carregar(execucao, csv.ler(baixado.get().caminho()).iterator());

        // `foraDaCoorte` alto e `resolvidos` baixo é o sintoma de cadastro
        // rodado antes da coorte, ou de coorte de outro ano -- e o efeito
        // seria site sem matéria, sem nenhum erro no caminho.
        log.info("cadastro da camara: {} resolvido(s), {} pendente(s) de curadoria, "
                 + "{} ambiguo(s), {} fora da coorte",
                 r.resolvidos(), r.pendentesDeCuradoria(), r.ambiguos(), r.foraDaCoorte());
        return true;
    }
}
