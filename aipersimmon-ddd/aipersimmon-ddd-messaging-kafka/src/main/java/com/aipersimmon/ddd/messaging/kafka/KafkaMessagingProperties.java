package com.aipersimmon.ddd.messaging.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Kafka integration-event transport, under {@code
 * aipersimmon.ddd.messaging.kafka}.
 */
@ConfigurationProperties("aipersimmon.ddd.messaging.kafka")
public class KafkaMessagingProperties {

  /**
   * A convenience default topic name events may reference from their {@code @Externalized} target,
   * e.g. {@code @Externalized("${aipersimmon.ddd.messaging.kafka.topic}")} to route an event to it.
   * It is <strong>not</strong> the routing key: routing is per event, from each event's
   * {@code @Externalized} annotation (see {@link ExternalizedRoutes}). An event with no
   * {@code @Externalized} stays LOCAL regardless of this value.
   */
  private String topic = "aipersimmon.integration-events";

  private final Producer producer = new Producer();

  private final Consumer consumer = new Consumer();

  public String getTopic() {
    return topic;
  }

  public void setTopic(String topic) {
    this.topic = topic;
  }

  public Producer getProducer() {
    return producer;
  }

  public Consumer getConsumer() {
    return consumer;
  }

  /** Settings for the outbox relay's Kafka dispatcher (the producer side). */
  public static class Producer {

    /**
     * Upper bound, in milliseconds, on awaiting a broker ack for one send before the relay gives up
     * and retries the row on the next poll. Bounds the single relay thread so one stuck send cannot
     * pin it (and stall all delivery on the instance), and so a wait cannot outlive the relay's
     * {@code lock-at-most-for} and let another instance dispatch the same rows concurrently. Keep
     * {@code outbox.batch-size × this} comfortably below the relay's {@code
     * relay.lock-at-most-for}.
     */
    private long sendTimeoutMs = 30000;

    public long getSendTimeoutMs() {
      return sendTimeoutMs;
    }

    public void setSendTimeoutMs(long sendTimeoutMs) {
      this.sendTimeoutMs = sendTimeoutMs;
    }
  }

  /** Settings for the in-process consumer bridge. */
  public static class Consumer {

    /**
     * Whether to run the {@link KafkaIntegrationEventListener} that consumes the topic and
     * republishes events in process. Off by default: a producer-only service does not need it.
     */
    private boolean enabled = false;

    /**
     * Skip a consumed record whose {@code (type, version)} has no local {@code @EventListener}:
     * since nothing would handle it, the bridge drops it before the inbox write / reconstruct /
     * republish instead of doing that work only for the event to match no listener. On by default.
     * Set {@code false} if the application consumes integration events through a mechanism the
     * startup scan cannot see (e.g. a programmatic {@code ApplicationListener} rather than an
     * {@code @EventListener} method), so nothing is skipped.
     */
    private boolean skipLocallyUnhandled = true;

    /**
     * Interval, in milliseconds, between retries of a <em>systemic</em> failure — one that signals
     * the environment is down (a {@link org.springframework.dao.DataAccessException}: DataSource
     * unavailable, connection pool exhausted, …), not that the message is bad. Such a failure is
     * retried <strong>indefinitely</strong> at this interval and is <strong>never</strong>
     * dead-lettered: the partition waits at the failed record until the environment recovers, so
     * healthy messages are not flooded into the DLT and per-aggregate order is preserved. Keep this
     * comfortably below Kafka's {@code max.poll.interval.ms} (default 5 min) so the blocking retry
     * does not trigger a rebalance.
     */
    private long systemicBackoffIntervalMs = 10000;

    private final Retry retry = new Retry();

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public boolean isSkipLocallyUnhandled() {
      return skipLocallyUnhandled;
    }

    public void setSkipLocallyUnhandled(boolean skipLocallyUnhandled) {
      this.skipLocallyUnhandled = skipLocallyUnhandled;
    }

    public long getSystemicBackoffIntervalMs() {
      return systemicBackoffIntervalMs;
    }

    public void setSystemicBackoffIntervalMs(long systemicBackoffIntervalMs) {
      this.systemicBackoffIntervalMs = systemicBackoffIntervalMs;
    }

    public Retry getRetry() {
      return retry;
    }

    /**
     * How a failed consume is retried before the record is dead-lettered to {@code <topic>.DLT}.
     * Bounded exponential backoff: a transient fault gets a few spaced retries, then the poison
     * record is quarantined rather than blocking the partition or being silently skipped. A
     * permanent failure (an unknown event type, a malformed payload) is not retried at all — it
     * goes straight to the DLT.
     */
    public static class Retry {

      /** Retries after the first delivery before dead-lettering (so N+1 attempts total). */
      private int maxRetries = 3;

      /** Backoff before the first retry. */
      private long initialIntervalMs = 1000;

      /** Multiplier applied to the interval after each retry. */
      private double multiplier = 2.0;

      /** Ceiling on the backoff interval. */
      private long maxIntervalMs = 10000;

      public int getMaxRetries() {
        return maxRetries;
      }

      public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
      }

      public long getInitialIntervalMs() {
        return initialIntervalMs;
      }

      public void setInitialIntervalMs(long initialIntervalMs) {
        this.initialIntervalMs = initialIntervalMs;
      }

      public double getMultiplier() {
        return multiplier;
      }

      public void setMultiplier(double multiplier) {
        this.multiplier = multiplier;
      }

      public long getMaxIntervalMs() {
        return maxIntervalMs;
      }

      public void setMaxIntervalMs(long maxIntervalMs) {
        this.maxIntervalMs = maxIntervalMs;
      }
    }
  }
}
