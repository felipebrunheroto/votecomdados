package br.org.votecomdados.api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Pede ao navegador para guardar a resposta por 30 segundos.
 *
 * <h2>O que isto compra, e o que não</h2>
 *
 * Os ~28 mil perfis não são pré-renderizados: cada visita a um perfil vira
 * chamada à API, que consulta o Postgres. É a única parte do site que escala
 * com tráfego — proposição e votação saem prontas do CloudFront.
 *
 * <p>Isto ajuda quem recarrega, volta pelo histórico ou reabre o mesmo perfil.
 * <b>Não ajuda num pico</b>: pico é muita gente diferente abrindo perfis
 * diferentes, e cache de navegador é por pessoa. Como a API não está atrás de
 * CDN, não existe cache compartilhado — se um dia a proteção contra pico virar
 * necessidade, é aí que ela mora, não aqui.
 *
 * <h2>Por que 30 segundos</h2>
 *
 * A ingestão roda uma vez por dia, então o dado mudaria pouco mesmo com
 * janelas bem maiores. Trinta segundos é a escolha conservadora: corta a
 * repetição imediata sem que ninguém veja informação velha por tempo
 * perceptível.
 *
 * <h2>Por que um filtro, e não anotação por endpoint</h2>
 *
 * A API inteira é leitura pública de dado público. Marcar endpoint a endpoint
 * significaria que o próximo a ser criado nasceria sem cache, sem ninguém
 * notar. Aqui a regra vale para todos, e a exceção seria explícita.
 *
 * <p>`public` e não `private` porque o dado é público de fato: se um dia uma
 * CDN entrar na frente, ela pode guardar — e é justamente o que faltaria.
 */
@Component
public class CacheDeLeitura extends OncePerRequestFilter {

    private static final String VALOR = "public, max-age=30";

    @Override
    protected void doFilterInternal(HttpServletRequest pedido, HttpServletResponse resposta,
                                    FilterChain cadeia) throws ServletException, IOException {
        cadeia.doFilter(pedido, resposta);

        // Só GET bem-sucedido. Cachear erro esconderia uma falha momentânea
        // atrás de meio minuto de repetição — e o 404 de um id inexistente
        // não deve sobreviver à correção do id.
        if ("GET".equals(pedido.getMethod())
            && resposta.getStatus() >= 200 && resposta.getStatus() < 300
            && !resposta.containsHeader("Cache-Control")) {
            resposta.setHeader("Cache-Control", VALOR);
        }
    }
}
