package com.aipersimmon.ddd.outbox.spring;

import java.util.Set;
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
 * relay.lock-name}, {@code cleanup.enabled}, {@code relay.enabled}) stay as property placeholders
 * on their annotations — annotation attributes cannot read a bound bean — so they are intentionally
 * not mirrored here. {@code dispatch} is the exception: the beans still select themselves by
 * placeholder, but it is bound here as well so that an unrecognised value is rejected outright
 * instead of matching no bean and surfacing as a missing dependency somewhere downstream.
 */
@ConfigurationProperties("aipersimmon.ddd.outbox")
public class OutboxProperties implements InitializingBean {

  /** Rows the relay claims and dispatches per poll. Must be {@code >= 1}. */
  private int batchSize = 100;

  /** Dispatch attempts before a row is dead-lettered. Must be {@code >= 1}. */
  private int maxAttempts = 10;

  /**
   * Which built-in dispatcher to use when no messaging starter and no custom {@code
   * OutboxDispatcher} bean supplies one: {@code in-process} (the default) republishes each relayed
   * event in process; {@code logging} only logs it and delivers nothing.
   *
   * <p>Bound here purely to be validated. The beans select themselves with
   * {@code @ConditionalOnProperty}, which cannot read a bound bean, but that also means an
   * unrecognised value quietly matches nothing — leaving no dispatcher at all and failing later as
   * an unsatisfied dependency of the relay, which reads as a packaging bug rather than a typo. A
   * transport is chosen by adding a starter, so a plausible guess like {@code dispatch=kafka} lands
   * exactly here.
   */
  private String dispatch = "in-process";

  /**
   * Allow startup when the application declares {@code @Externalized} events but the active
   * dispatcher cannot reach an external target — accepting that those events are marked sent
   * without leaving the process. Off by default: that is silent data loss, and the point of the
   * guard is that nothing else would reveal it. Switch it on for a deliberately broker-less run.
   */
  private boolean allowUnreachableExternalEvents = false;

  private final Retry retry = new Retry();

  private final Cleanup cleanup = new Cleanup();

  /** The dispatch modes this starter can wire itself; anything else is a configuration error. */
  private static final Set<String> DISPATCH_MODES = Set.of("in-process", "logging");

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
    if (dispatch == null || !DISPATCH_MODES.contains(dispatch)) {
      throw new IllegalStateException(
          "aipersimmon.ddd.outbox.dispatch must be one of "
              + DISPATCH_MODES
              + ", got '"
              + dispatch
              + "'. A broker transport is not selected here — add its starter (e.g."
              + " aipersimmon-ddd-messaging-kafka) or define your own OutboxDispatcher bean, and"
              + " leave this unset.");
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

  public String getDispatch() {
    return dispatch;
  }

  public void setDispatch(String dispatch) {
    this.dispatch = dispatch;
  }

  public boolean isAllowUnreachableExternalEvents() {
    return allowUnreachableExternalEvents;
  }

  public void setAllowUnreachableExternalEvents(boolean allowUnreachableExternalEvents) {
    this.allowUnreachableExternalEvents = allowUnreachableExternalEvents;
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
