package br.org.votecomdados.api.web;

import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Arrays;
import java.util.stream.Stream;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Envelope único de erro: `{"error": {"code", "message"}}`, conforme
 * docs/API.md. Nenhum endpoint deve inventar formato próprio — o frontend
 * trata um só.
 */
@RestControllerAdvice
public class Erros {

    public record Envelope(Erro error) {
        public record Erro(String code, String message) {}

        static Envelope de(String code, String message) {
            return new Envelope(new Erro(code, message));
        }
    }

    public static class NaoEncontrado extends RuntimeException {
        public NaoEncontrado(String mensagem) {
            super(mensagem);
        }
    }

    @ExceptionHandler(NaoEncontrado.class)
    ResponseEntity<Envelope> naoEncontrado(NaoEncontrado e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Envelope.de("NOT_FOUND", e.getMessage()));
    }

    /**
     * Violação de `@Min`/`@Max`/`@Size` em parâmetro de query.
     *
     * O interceptor do `@Validated` lança ConstraintViolationException, e não
     * HandlerMethodValidationException — sem tratar este tipo, o limite de
     * `pageSize` virava 500 em vez de 400, e a proteção contra varredura ampla
     * aparecia ao cliente como falha nossa.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Envelope> parametroInvalido(ConstraintViolationException e) {
        // A mensagem crua traz o nome do método ("listar.pageSize"); só o
        // nome do parâmetro interessa a quem chama.
        String detalhe = e.getConstraintViolations().stream()
            .map(v -> {
                String caminho = v.getPropertyPath().toString();
                String parametro = caminho.contains(".")
                    ? caminho.substring(caminho.lastIndexOf('.') + 1) : caminho;
                return parametro + ": " + v.getMessage();
            })
            .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(Envelope.de("BAD_REQUEST", detalhe));
    }

    /**
     * Valor de query param que não converte para o tipo esperado — tipicamente
     * um enum fora do domínio, como `cargo=IMPERADOR`.
     *
     * Sem este tratamento a resposta era 500: a plataforma reportava como falha
     * interna dela algo que é erro de quem chamou, e ainda registrava a
     * ocorrência como incidente no log.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Envelope> tipoIncompativel(MethodArgumentTypeMismatchException e) {
        Class<?> esperado = e.getRequiredType();
        String valoresAceitos = esperado != null && esperado.isEnum()
            ? " Valores aceitos: " + Stream.of(esperado.getEnumConstants())
                .map(Object::toString).reduce((a, b) -> a + ", " + b).orElse("")
            : "";
        return ResponseEntity.badRequest().body(Envelope.de("BAD_REQUEST",
            "Valor inválido para '" + e.getName() + "': " + e.getValue() + "." + valoresAceitos));
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HandlerMethodValidationException.class,
        IllegalArgumentException.class
    })
    ResponseEntity<Envelope> requisicaoInvalida(Exception e) {
        return ResponseEntity.badRequest()
            .body(Envelope.de("BAD_REQUEST", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Envelope> falhaInterna(Exception e) {
        // A mensagem interna não vai para o cliente: pode conter SQL ou nomes
        // de coluna. O rastreamento fica no log estruturado.
        org.slf4j.LoggerFactory.getLogger(Erros.class)
            .error("falha nao tratada ao atender requisicao", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Envelope.de("INTERNAL_ERROR", "Erro interno ao processar a requisição."));
    }
}
