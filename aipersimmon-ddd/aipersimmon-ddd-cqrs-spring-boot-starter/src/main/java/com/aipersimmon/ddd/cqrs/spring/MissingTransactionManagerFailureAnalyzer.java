package com.aipersimmon.ddd.cqrs.spring;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Turns a missing transaction manager into an actionable startup report: what the starter can no
 * longer guarantee, and the two ways out.
 *
 * <p>Registered via {@code META-INF/spring.factories}.
 */
public class MissingTransactionManagerFailureAnalyzer
    extends AbstractFailureAnalyzer<MissingTransactionManagerException> {

  @Override
  protected FailureAnalysis analyze(
      Throwable rootFailure, MissingTransactionManagerException cause) {
    String description =
        "The aipersimmon-ddd CQRS starter is present but no PlatformTransactionManager bean exists. "
            + "The starter's guarantee is that one command is one transaction — the aggregate write, "
            + "the outbox row and the domain events commit together or not at all. Without a "
            + "transaction manager the UnitOfWork and the transaction interceptor are both absent, "
            + "every command runs untransacted, and a failure part-way through a handler leaves "
            + "whatever it had already written. Nothing at runtime would report that.";
    String action =
        "Pick one.\n\n"
            + "1. Give the application a transaction manager. A DataSource is enough — Spring Boot's "
            + "DataSourceTransactionManagerAutoConfiguration contributes one:\n\n"
            + "    spring.datasource.url: jdbc:postgresql://...\n\n"
            + "   Declare one explicitly if you manage it yourself (JTA, several data sources, a "
            + "non-JDBC store).\n\n"
            + "2. Declare that this application deliberately runs without transactions — a read-only "
            + "service, or handlers that touch no database:\n\n"
            + "    aipersimmon.ddd.cqrs.transaction.required: false\n\n"
            + "   Option 2 logs a WARN on every start, on purpose: it must not become the unnoticed "
            + "state of a service that later grows a database.";
    return new FailureAnalysis(description, action, cause);
  }
}
