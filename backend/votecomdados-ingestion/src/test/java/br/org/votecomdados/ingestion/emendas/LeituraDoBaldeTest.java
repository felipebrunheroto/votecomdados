package br.org.votecomdados.ingestion.emendas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.org.votecomdados.ingestion.armazenamento.ArmazenamentoDeObjetos;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * A leitura do JSON do bucket — o caminho que substituiu a chamada à API.
 *
 * <p>Sem banco nem rede: o armazenamento é substituído por um dublê que
 * devolve um arquivo de verdade em disco. O que se verifica aqui é o que
 * costuma passar despercebido — a guarda contra arquivo vazio e a mensagem
 * que a pessoa vai ler quando o arquivo não estiver lá.
 */
class LeituraDoBaldeTest {

    /** Armazenamento que entrega um arquivo já existente, ou estoura. */
    private static ArmazenamentoDeObjetos entregando(Path arquivo) {
        return new ArmazenamentoDeObjetos(() -> null) {
            @Override
            public Path baixar(String uri, Path diretorio) {
                if (arquivo == null) {
                    throw new IllegalStateException("NoSuchKey: " + uri);
                }
                return arquivo;
            }
        };
    }

    private static JobDeEmendas job(Path arquivo, String balde) {
        return new JobDeEmendas(entregando(arquivo), null, new ObjectMapper(), balde);
    }

    /**
     * Quem encontrar esta falha precisa saber que existe uma Lambda em outra
     * região no caminho. "Objeto não existe" mandaria a pessoa procurar o
     * problema no lugar errado.
     */
    @Test
    void arquivo_ausente_explica_quem_produz_o_arquivo(@TempDir Path dir) {
        assertThatThrownBy(() -> job(null, "balde-teste").carregar(2025, dir))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("s3://balde-teste/entrada/emendas-2025.json")
            .hasMessageContaining("Buscar emendas")
            .hasMessageContaining("fora do Brasil");
    }

    /**
     * Lista vazia não é "ano sem emendas": é defeito. Seguir em frente com
     * zero linhas faria a execução ser marcada como concluída, movendo o
     * watermark sobre um carregamento que não carregou nada.
     */
    @Test
    void json_vazio_estoura_em_vez_de_carregar_nada(@TempDir Path dir) throws Exception {
        Path vazio = Files.writeString(dir.resolve("emendas-2025.json"), "[]");

        assertThatThrownBy(() -> job(vazio, "balde-teste").carregar(2025, dir))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("nao tem emenda nenhuma");
    }

    @Test
    void balde_nao_configurado_diz_o_nome_da_propriedade(@TempDir Path dir) {
        assertThatThrownBy(() -> job(null, "").carregar(2025, dir))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("votecomdados.balde.ingestao");
    }

    /** O caminho do objeto é derivado do ano, não digitado por quem opera. */
    @Test
    void a_uri_e_derivada_do_ano(@TempDir Path dir) {
        assertThatThrownBy(() -> job(null, "meu-balde").carregar(2024, dir))
            .hasMessageContaining("s3://meu-balde/entrada/emendas-2024.json");
    }
}
