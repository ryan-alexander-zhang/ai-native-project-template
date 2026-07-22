package com.example;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Top-level OpenAPI document metadata (title, version, servers). springdoc — pulled in by {@code
 * aipersimmon-ddd-openapi-spring-boot-starter} — builds the operations from the controllers by
 * reflection and applies the framework's problem-response customizer; this bean only supplies the
 * document-level {@code info} and {@code servers} it cannot infer.
 *
 * <p>It lives in the composition root ({@code com.example}, no layer segment), the one place
 * allowed to touch web/OpenAPI wiring — the bounded-context modules stay free of any OpenAPI
 * dependency.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

  @Bean
  OpenAPI orderingOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Ordering API")
                .version("v1")
                .description(
                    "REST API for the ordering bounded context. Errors are RFC 9457 "
                        + "problem+json; success responses return the resource directly."))
        .servers(List.of(new Server().url("/").description("This service")));
  }
}
