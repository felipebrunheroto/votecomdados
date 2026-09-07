package br.org.votecomdados.ingestion.armazenamento;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
class ConfiguracaoDeArmazenamento {

    /**
     * {@code S3Client::create} passado como fornecedor, não como cliente já
     * construído: a construção resolve região e credenciais na hora, e em
     * desenvolvimento local não há nenhuma das duas. Adiar isso mantém o
     * worker subindo igual no {@code docker compose} e nos testes de
     * integração — quem não usa {@code s3://} nunca constrói cliente nenhum.
     *
     * <p>Em produção, a cadeia padrão do SDK resolve credencial pela task
     * role do ECS ({@code votecomdados-task-ingestion}) e região pela
     * variável de ambiente da task. Nenhuma chave estática em lugar nenhum.
     */
    @Bean
    ArmazenamentoDeObjetos armazenamentoDeObjetos() {
        return new ArmazenamentoDeObjetos(S3Client::create);
    }
}
