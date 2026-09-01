package br.org.votecomdados.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS.
 *
 * Sem isto a API funciona no curl e é inutilizável pelo navegador: o frontend
 * estático é servido de outra origem (CDN) e o browser bloqueia a resposta.
 *
 * A origem permitida é `*` por padrão, e isso é decisão de produto, não
 * descuido: a API é pública, somente leitura, sem autenticação e sem cookies,
 * então não há sessão para um site terceiro sequestrar. Liberar o consumo
 * direto por jornalistas e pesquisadores é coerente com a missão de
 * transparência — restringir a origem só protegeria os dados de quem já pode
 * baixá-los das fontes oficiais.
 *
 * Como não há credenciais envolvidas, `allowCredentials` permanece falso; é o
 * que torna `*` seguro aqui.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String origensPermitidas;

    CorsConfig(@Value("${app.cors.origens:*}") String origensPermitidas) {
        this.origensPermitidas = origensPermitidas;
    }

    @Override
    public void addCorsMappings(CorsRegistry registro) {
        registro.addMapping("/api/**")
            .allowedOriginPatterns(origensPermitidas.split(","))
            .allowedMethods("GET", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(false)
            .maxAge(3600);
    }
}
