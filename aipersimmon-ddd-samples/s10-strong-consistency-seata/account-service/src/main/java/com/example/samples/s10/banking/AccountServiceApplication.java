package com.example.samples.s10.banking;

import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * S10 account service: holds the money and starts the global transaction.
 *
 * <p><strong>Two deviations from the usual Spring Boot shape, both forced by the same thing.</strong> The
 * end-to-end tests boot this service and the points service in one JVM, on one classpath — and two
 * applications cannot share a classpath naively.
 *
 * <ul>
 *   <li>The base package is {@code ..s10.banking}, not {@code ..s10}. At {@code ..s10} this application's
 *       component scan would swallow the other service's controllers, mappers and repositories.
 *   <li>Its configuration file is {@code account-service.yaml}, not {@code application.yaml}, and {@link
 *       #application()} names it. Both services shipping an {@code application.yaml} means exactly one of
 *       them wins the classpath and the other silently boots on its neighbour's datasource — which is how
 *       this was found.
 * </ul>
 *
 * <p>Both were invisible while each service ran alone, and neither is a test-only concern: a service's base
 * package and its configuration resource name are part of how it coexists with anything else in a JVM.
 * {@link #application()} exists so the harness and {@code main} cannot drift apart on either.
 */
@org.springframework.boot.autoconfigure.SpringBootApplication
public class AccountServiceApplication {

  /** The one place that knows how this service is configured. Used by {@code main} and by the tests. */
  public static SpringApplicationBuilder application() {
    return new SpringApplicationBuilder(AccountServiceApplication.class)
        .properties("spring.config.name=account-service");
  }

  public static void main(String[] args) {
    application().run(args);
  }
}
