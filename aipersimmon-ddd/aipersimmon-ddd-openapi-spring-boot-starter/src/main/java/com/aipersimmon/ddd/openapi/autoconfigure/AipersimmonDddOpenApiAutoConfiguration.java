package com.aipersimmon.ddd.openapi.autoconfigure;

import io.swagger.v3.oas.models.OpenAPI;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Wires this library's OpenAPI contributions on top of springdoc. springdoc itself (the {@code
 * /v3/api-docs} scanner and Swagger UI) auto-configures from its own starter on the classpath; here
 * we only add the pieces reflection over controllers cannot produce.
 *
 * <p>Guarded so it is inert unless wanted: {@link ConditionalOnClass} on the springdoc extension
 * point and the swagger model keeps it off when the springdoc runtime is absent, {@link
 * ConditionalOnWebApplication} restricts it to servlet web apps, and {@code
 * aipersimmon.ddd.openapi.enabled} (default true) is the runtime off switch. Every bean is {@link
 * ConditionalOnMissingBean}, so an application can replace any contribution with its own.
 */
@AutoConfiguration
@ConditionalOnClass({GlobalOpenApiCustomizer.class, OpenAPI.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(
    prefix = "aipersimmon.ddd.openapi",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@EnableConfigurationProperties(AipersimmonDddOpenApiProperties.class)
public class AipersimmonDddOpenApiAutoConfiguration {

  /**
   * Documents the framework's RFC 9457 problem responses on every operation. Gated by {@code
   * aipersimmon.ddd.openapi.default-problem-responses} (default true) so an app that documents its
   * error contract differently can turn just this off while keeping springdoc.
   */
  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(
      prefix = "aipersimmon.ddd.openapi",
      name = "default-problem-responses",
      havingValue = "true",
      matchIfMissing = true)
  ProblemResponsesCustomizer aipersimmonProblemResponsesCustomizer() {
    return new ProblemResponsesCustomizer();
  }
}
