package com.aipersimmon.ddd.cqrs.spring;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the CQRS starter, under {@code aipersimmon.ddd.cqrs}. */
@ConfigurationProperties(prefix = "aipersimmon.ddd.cqrs")
public class AipersimmonDddCqrsProperties {

  private final Transaction transaction = new Transaction();

  private final RetryOnConflict retryOnConflict = new RetryOnConflict();

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

  /**
   * Bounded automatic retry of commands that lost an optimistic-lock race — see {@link
   * RetryOnConflictCommandInterceptor} for the full argument, including why it is opt-in (the
   * deployment, not the framework, can assert that its handlers have no non-transactional side
   * effects to repeat).
   */
  public static class RetryOnConflict {

    /** Off by default: enabling it asserts the handlers are safe to rerun. */
    private boolean enabled = false;

    /** Total attempts, the first included; 3 means "retry twice, then let the conflict stand". */
    private int maxAttempts = 3;

    /** Backoff before the first retry; doubles per further retry. */
    private Duration initialBackoff = Duration.ofMillis(50);

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public int getMaxAttempts() {
      return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
      this.maxAttempts = maxAttempts;
    }

    public Duration getInitialBackoff() {
      return initialBackoff;
    }

    public void setInitialBackoff(Duration initialBackoff) {
      this.initialBackoff = initialBackoff;
    }
  }

  public Transaction getTransaction() {
    return transaction;
  }

  public RetryOnConflict getRetryOnConflict() {
    return retryOnConflict;
  }
}
