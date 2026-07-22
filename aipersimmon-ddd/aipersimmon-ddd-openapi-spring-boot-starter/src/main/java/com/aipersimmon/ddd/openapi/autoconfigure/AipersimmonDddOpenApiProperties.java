package com.aipersimmon.ddd.openapi.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the OpenAPI starter, under {@code aipersimmon.ddd.openapi}.
 *
 * <p>The starter is opt-in at the dependency level (adding it is the switch), so {@link #enabled}
 * is the escape hatch to keep the starter on the classpath but suppress its contribution — e.g. an
 * app that wires its own OpenAPI customization. {@link #defaultProblemResponses} governs only the
 * cross-cutting RFC 9457 responses; springdoc's reflection-based documentation of controllers is
 * unaffected either way.
 */
@ConfigurationProperties("aipersimmon.ddd.openapi")
public class AipersimmonDddOpenApiProperties {

  /** Whether this starter's OpenAPI contributions are active. */
  private boolean enabled = true;

  /**
   * Whether to attach the default family of {@code application/problem+json} (RFC 9457) responses
   * (400/404/429/500) to every operation. An operation that declares its own response for one of
   * those status codes keeps it; the default is added only for the codes it does not declare.
   */
  private boolean defaultProblemResponses = true;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public boolean isDefaultProblemResponses() {
    return defaultProblemResponses;
  }

  public void setDefaultProblemResponses(boolean defaultProblemResponses) {
    this.defaultProblemResponses = defaultProblemResponses;
  }
}
