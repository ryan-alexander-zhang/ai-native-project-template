package com.aipersimmon.ddd.cqrs.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the CQRS starter, under {@code aipersimmon.ddd.cqrs}. */
@ConfigurationProperties(prefix = "aipersimmon.ddd.cqrs")
public class AipersimmonDddCqrsProperties {

  private final Transaction transaction = new Transaction();

  /** How the command bus behaves with respect to transactions. */
  public static class Transaction {

    /**
     * Whether a {@code PlatformTransactionManager} is required for the application to start.
     *
     * <p>On, because the starter's headline guarantee is that one command is one transaction: the
     * aggregate write, the outbox row and the domain events commit together or not at all. Without
     * a transaction manager both the {@code UnitOfWork} and the transaction interceptor back off,
     * every command runs untransacted, and nothing says so — the guarantee evaporates while the
     * code that relied on it keeps compiling and passing.
     *
     * <p>Turn it off for a deliberately transaction-less deployment: a read-only service, or a bus
     * whose handlers touch no database. That is a real shape, so it is a property rather than a
     * hard requirement — but it must be chosen, and it is logged at WARN on every start so it
     * cannot become the accidental state of a service that later grows a database.
     */
    private boolean required = true;

    public boolean isRequired() {
      return required;
    }

    public void setRequired(boolean required) {
      this.required = required;
    }
  }

  public Transaction getTransaction() {
    return transaction;
  }
}
