package br.org.votecomdados.ingestion.armazenamento;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Contra um S3 de verdade (LocalStack), não contra mock — a mesma disciplina
 * dos golden files: o que se quer provar aqui é o comportamento do serviço,
 * e um dublê só provaria que o dublê faz o que mandaram.
 */
@Testcontainers
class ArmazenamentoDeObjetosTest {

    private static final String BUCKET = "votecomdados-teste";

    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
        DockerImageName.parse("localstack/localstack:3"))
        .withServices(LocalStackContainer.Service.S3);

    static S3Client s3;

    @BeforeAll
    static void prepararBucket() {
        s3 = S3Client.builder()
            .endpointOverride(URI.create(LOCALSTACK.getEndpoint().toString()))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
            .region(Region.of(LOCALSTACK.getRegion()))
            .forcePathStyle(true)
            .build();
        s3.createBucket(b -> b.bucket(BUCKET));
    }

    private ArmazenamentoDeObjetos armazenamento() {
        return new ArmazenamentoDeObjetos(() -> s3);
    }

    @Test
    void reconhece_o_que_e_remoto_e_o_que_e_caminho_local() {
        assertThat(ArmazenamentoDeObjetos.ehRemoto("s3://balde/chave")).isTrue();
        assertThat(ArmazenamentoDeObjetos.ehRemoto("/tmp/consulta_cand_2026.zip")).isFalse();
        assertThat(ArmazenamentoDeObjetos.ehRemoto(null)).isFalse();
    }

    @Test
    void uri_sem_chave_e_rejeitada_com_mensagem_util() {
        assertThatThrownBy(() -> ArmazenamentoDeObjetos.Referencia.de("s3://so-o-balde"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("bucket/chave");
    }

    @Test
    void baixar_preserva_o_nome_do_arquivo(@TempDir Path destino) {
        // O leitor do TSE decide entre zip e csv pela extensão — se o download
        // renomeasse o arquivo, a coorte leria o formato errado.
        s3.putObject(b -> b.bucket(BUCKET).key("entrada/consulta_cand_2026.zip"),
                     RequestBody.fromString("conteudo-qualquer"));

        Path baixado = armazenamento()
            .baixar("s3://" + BUCKET + "/entrada/consulta_cand_2026.zip", destino);

        assertThat(baixado.getFileName()).hasToString("consulta_cand_2026.zip");
        assertThat(baixado).content(StandardCharsets.UTF_8).isEqualTo("conteudo-qualquer");
    }

    @Test
    void enviar_diretorio_preserva_a_estrutura(@TempDir Path origem) throws Exception {
        Files.writeString(origem.resolve("politico.csv"), "id,nome\n1,Fulano\n");
        Files.createDirectories(origem.resolve("sub"));
        Files.writeString(origem.resolve("sub/manifesto.json"), "{}");

        armazenamento().enviarDiretorio(origem, "s3://" + BUCKET + "/dados-abertos/2026-09-07");

        var chaves = s3.listObjectsV2(b -> b.bucket(BUCKET).prefix("dados-abertos/2026-09-07/"))
                       .contents().stream().map(o -> o.key()).toList();
        assertThat(chaves).containsExactlyInAnyOrder(
            "dados-abertos/2026-09-07/politico.csv",
            "dados-abertos/2026-09-07/sub/manifesto.json");
    }

    @Test
    void existeAlgoSob_e_o_que_protege_o_instantaneo_ja_publicado() {
        // É esta checagem que impede o upload de sobrescrever em silêncio um
        // pacote já citado por alguém: o exportador gera num diretório
        // temporário, sempre novo, então a checagem em disco dele nunca
        // acusaria nada aqui (ver SeletorDeJob.publicarNoObjectStorage).
        String uri = "s3://" + BUCKET + "/dados-abertos/2026-01-01";
        assertThat(armazenamento().existeAlgoSob(uri)).isFalse();

        s3.putObject(b -> b.bucket(BUCKET).key("dados-abertos/2026-01-01/politico.csv"),
                     RequestBody.fromString("id,nome\n"));

        assertThat(armazenamento().existeAlgoSob(uri)).isTrue();
    }

    @Test
    void prefixo_parecido_nao_conta_como_ja_publicado() {
        // "2026-02-01" não pode ser considerado publicado só porque existe
        // "2026-02-011" ou "2026-02-01-rascunho" — a comparação é por
        // diretório, e um falso positivo aqui bloquearia a publicação do dia.
        s3.putObject(b -> b.bucket(BUCKET).key("dados-abertos/2026-02-01-rascunho/x.csv"),
                     RequestBody.fromString("x"));

        assertThat(armazenamento().existeAlgoSob("s3://" + BUCKET + "/dados-abertos/2026-02-01"))
            .isFalse();
    }
}
