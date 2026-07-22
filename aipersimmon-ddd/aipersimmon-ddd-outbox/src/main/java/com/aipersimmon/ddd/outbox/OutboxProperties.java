package com.aipersimmon.ddd.outbox;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound, storage-agnostic outbox relay configuration ({@code aipersimmon.ddd.outbox.*}), replacing
 * the scattered {@code @Value} injections that carried no validation. Registered via
 * {@code @EnableConfigurationProperties} by {@link AipersimmonDddOutboxAutoConfiguration}, and
 * consumed by the storage starters ({@code -outbox-jdbc}, {@code -outbox-mybatis-plus}) when they
 * build the relay and cleanup beans.
 *
 * <p>Validation is explicit and dependency-free (no {@code jakarta.validation}): {@link
 * #afterPropertiesSet()} rejects values that would silently misbehave rather than fail — a
 * non-positive {@code batch-size} makes the relay poll zero rows and never progress; a non-positive
 * {@code max-attempts} dead-letters a healthy message on its first failure; a negative backoff or
 * {@code max-backoff < base-backoff} inverts the retry schedule; a negative {@code
 * retention-seconds} puts the cleanup cutoff in the future and deletes still-live rows. Binding a
 * bad value fails startup with a concrete message.
 *
 * <p>The scheduling-annotation knobs ({@code poll-delay-ms}, {@code relay.lock-at-most-for}, {@code
 * relay.lock-name}, {@code cleanup.enabled}) and the {@code dispatch} mode stay as property
 * placeholders on their annotations — annotation attributes cannot read a bound bean — so they are
 * intentionally not mirrored here.
 */
@ConfigurationProperties("aipersimmon.ddd.outbox")
public class OutboxProperties implements InitializingBean {

  /** Rows the relay claims and dispatches per poll. Must be {@code >= 1}. */
  private int batchSize = 100;

  /** Dispatch attempts before a row is dead-lettered. Must be {@code >= 1}. */
  private int maxAttempts = 10;

  private final Retry retry = new Retry();

  private final Cleanup cleanup = new Cleanup();

  @Override
  public void afterPropertiesSet() {
    if (batchSize < 1) {
      throw new IllegalStateException(
          "aipersimmon.ddd.outbox.batch-size must be >= 1 (a non-positive batch polls zero rows and"
              + " never makes progress), got "
              + batchSize);
    }
    if (maxAttempts < 1) {
      throw new IllegalStateException(
          "aipersimmon.ddd.outbox.max-attempts must be >= 1 (a non-positive limit dead-letters a"
              + " healthy message on its first failure), got "
              + maxAttempts);
    }
    if (retry.baseBackoffMs < 0) {
      throw new IllegalStateException(
          "aipersimmon.ddd.outbox.retry.base-backoff-ms must be >= 0, got " + retry.baseBackoffMs);
    }
    if (retry.maxBackoffMs < retry.baseBackoffMs) {
      throw new IllegalStateException(
          "aipersimmon.ddd.outbox.retry.max-backoff-ms ("
              + retry.maxBackoffMs
              + ") must be >= base-backoff-ms ("
              + retry.baseBackoffMs
              + ")");
    }
    if (cleanup.retentionSeconds < 0) {
      throw new IllegalStateException(
          "aipersimmon.ddd.outbox.cleanup.retention-seconds must be >= 0 (a negative retention puts"
              + " the cutoff in the future and deletes still-live rows), got "
              + cleanup.retentionSeconds);
    }
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int batchSize) {
    this.batchSize = batchSize;
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public void setMaxAttempts(int maxAttempts) {
    this.maxAttempts = maxAttempts;
  }

  public Retry getRetry() {
    return retry;
  }

  public Cleanup getCleanup() {
    return cleanup;
  }

  /** Exponential-backoff bounds for a retried dispatch. */
  public static class Retry {

    private long baseBackoffMs = 1000;

    private long maxBackoffMs = 60000;

    public long getBaseBackoffMs() {
      return baseBackoffMs;
    }

    public void setBaseBackoffMs(long baseBackoffMs) {
      this.baseBackoffMs = baseBackoffMs;
    }

    public long getMaxBackoffMs() {
      return maxBackoffMs;
    }

    public void setMaxBackoffMs(long maxBackoffMs) {
      this.maxBackoffMs = maxBackoffMs;
    }
  }

  /** Retention for the periodic deletion of already-sent rows. */
  public static class Cleanup {

    private long retentionSeconds = 604800;

    public long getRetentionSeconds() {
      return retentionSeconds;
    }

    public void setRetentionSeconds(long retentionSeconds) {
      this.retentionSeconds = retentionSeconds;
    }
  }
}
