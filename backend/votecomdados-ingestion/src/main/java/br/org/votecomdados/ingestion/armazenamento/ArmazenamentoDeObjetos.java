package br.org.votecomdados.ingestion.armazenamento;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * A ponte entre os caminhos de arquivo que a ingestão sempre usou e o object
 * storage onde eles de fato vivem em produção.
 *
 * <h2>Por que isto existe</h2>
 *
 * Dois buracos apareceram na primeira tentativa de rodar a ingestão em
 * produção (07/09/2026), e são o mesmo buraco visto de dois lados:
 *
 * <ol>
 *   <li><b>Entrada:</b> {@code --job=COORTE} exige {@code --arquivo}
 *       apontando para um {@code consulta_cand_*.zip} legível no disco. O
 *       arquivo é baixado à mão porque o TSE bloqueia parte das máquinas
 *       (ver PLANO_IMPLEMENTACAO.md, W3) — e não havia como entregá-lo
 *       dentro de um container Fargate.</li>
 *   <li><b>Saída:</b> ARQUITETURA.md § 8b diz que a ingestão publica o pacote
 *       de dados abertos no object storage, e a task role até concede
 *       {@code s3:PutObject} para isso — mas o exportador só sabia escrever
 *       em disco local. O pacote nunca chegava a lugar nenhum.</li>
 * </ol>
 *
 * <h2>O que NÃO muda</h2>
 *
 * {@link br.org.votecomdados.ingestion.publicacao.ExportadorDeDadosAbertos}
 * continua falando só de {@link Path}: a lógica dele é {@code COPY} de view
 * para CSV, não transporte. Esta classe é o transporte, e só é acionada
 * quando o argumento começa com {@code s3://} — caminho local segue
 * funcionando igual, que é o que o {@code docker compose} de
 * desenvolvimento usa.
 */
public class ArmazenamentoDeObjetos {

    private static final Logger log = LoggerFactory.getLogger(ArmazenamentoDeObjetos.class);
    private static final String ESQUEMA = "s3://";

    /**
     * Fornecedor preguiçoso de propósito: construir um {@code S3Client} exige
     * região resolvível, e em desenvolvimento local (docker compose, testes de
     * integração) não há nenhuma. Se o cliente fosse criado na subida do
     * contexto, o worker deixaria de funcionar localmente por causa de um
     * recurso que ele nem vai usar.
     */
    private final Supplier<S3Client> fornecedor;
    private S3Client cliente;

    public ArmazenamentoDeObjetos(Supplier<S3Client> fornecedor) {
        this.fornecedor = fornecedor;
    }

    public static boolean ehRemoto(String destino) {
        return destino != null && destino.startsWith(ESQUEMA);
    }

    /**
     * Baixa {@code s3://bucket/chave} para dentro de {@code diretorio},
     * preservando o nome do arquivo — o leitor do TSE decide entre zip e csv
     * pela extensão, então o nome importa.
     */
    public Path baixar(String uri, Path diretorio) {
        Referencia ref = Referencia.de(uri);
        String nome = ref.chave().substring(ref.chave().lastIndexOf('/') + 1);
        Path destino = diretorio.resolve(nome);

        log.info("baixando {} para {}", uri, destino);
        cliente().getObject(b -> b.bucket(ref.bucket()).key(ref.chave()), destino);
        return destino;
    }

    /**
     * Diz se já existe algum objeto sob {@code s3://bucket/prefixo/}.
     *
     * <p>Existe para preservar, no S3, a mesma garantia que o exportador dá em
     * disco: <b>instantâneo datado não é sobrescrito</b>. Sem esta checagem, o
     * exportador geraria num diretório temporário (sempre novo, logo sempre
     * "livre") e o upload sobrescreveria em silêncio um pacote já publicado —
     * exatamente o que a regra existe para impedir, já que um arquivo que muda
     * embaixo de quem o citou não serve de evidência.
     */
    public boolean existeAlgoSob(String uriPrefixo) {
        Referencia ref = Referencia.de(uriPrefixo);
        String prefixo = ref.chave().endsWith("/") ? ref.chave() : ref.chave() + "/";
        var resposta = cliente().listObjectsV2(ListObjectsV2Request.builder()
            .bucket(ref.bucket()).prefix(prefixo).maxKeys(1).build());
        return resposta.keyCount() != null && resposta.keyCount() > 0;
    }

    /** Envia o conteúdo de {@code diretorio} para {@code s3://bucket/prefixo/}. */
    public void enviarDiretorio(Path diretorio, String uriPrefixo) {
        Referencia ref = Referencia.de(uriPrefixo);
        String prefixo = ref.chave().endsWith("/") ? ref.chave() : ref.chave() + "/";

        List<Path> arquivos;
        try (Stream<Path> caminhada = Files.walk(diretorio)) {
            arquivos = caminhada.filter(Files::isRegularFile).sorted(Comparator.naturalOrder())
                                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("nao consegui listar " + diretorio, e);
        }

        for (Path arquivo : arquivos) {
            String chave = prefixo + diretorio.relativize(arquivo).toString().replace('\\', '/');
            cliente().putObject(PutObjectRequest.builder()
                .bucket(ref.bucket()).key(chave).build(), RequestBody.fromFile(arquivo));
        }
        log.info("publicados {} arquivo(s) em {}", arquivos.size(), uriPrefixo);
    }

    private synchronized S3Client cliente() {
        if (cliente == null) {
            cliente = fornecedor.get();
        }
        return cliente;
    }

    /** {@code s3://bucket/uma/chave} partido nas duas metades que a API pede. */
    record Referencia(String bucket, String chave) {
        static Referencia de(String uri) {
            if (!ehRemoto(uri)) {
                throw new IllegalArgumentException(uri + " nao comeca com " + ESQUEMA);
            }
            String semEsquema = uri.substring(ESQUEMA.length());
            int barra = semEsquema.indexOf('/');
            if (barra < 0 || barra == semEsquema.length() - 1) {
                throw new IllegalArgumentException(
                    uri + " nao tem bucket e chave (esperado " + ESQUEMA + "bucket/chave)");
            }
            return new Referencia(semEsquema.substring(0, barra), semEsquema.substring(barra + 1));
        }
    }
}
