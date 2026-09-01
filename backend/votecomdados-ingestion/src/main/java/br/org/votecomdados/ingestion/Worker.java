package br.org.votecomdados.ingestion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Worker de ingestão: sobe, faz um job, morre.
 *
 * <p>Não expõe porta e não fica de pé. É o oposto da API, e a diferença é de
 * custo: um serviço 24/7 se paga por hora ligada, um job em lote se paga por
 * segundo de trabalho — e a ingestão trabalha poucos minutos por dia.
 *
 * <pre>
 *   java -jar worker.jar --job=COORTE --fonte=TSE
 *   java -jar worker.jar --job=BACKFILL --fonte=CAMARA --ano=2023
 *   java -jar worker.jar --job=INCREMENTAL --fonte=CAMARA
 * </pre>
 */
@SpringBootApplication
public class Worker {

    public static void main(String[] args) {
        // exit() propaga o código de saída do runner: um job que falhou precisa
        // sair diferente de zero, senão o scheduler o considera bem-sucedido e
        // a falha vira silêncio — que é o que este projeto mais combate.
        System.exit(SpringApplication.exit(SpringApplication.run(Worker.class, args)));
    }
}
