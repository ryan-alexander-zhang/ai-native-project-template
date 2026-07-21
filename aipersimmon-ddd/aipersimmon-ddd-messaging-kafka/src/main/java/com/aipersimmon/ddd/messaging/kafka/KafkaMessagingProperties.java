package com.aipersimmon.ddd.messaging.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Kafka integration-event transport, under
 * {@code aipersimmon.ddd.messaging.kafka}.
 */
@ConfigurationProperties("aipersimmon.ddd.messaging.kafka")
public class KafkaMessagingProperties {

    /**
     * A convenience default topic name events may reference from their {@code @Externalized}
     * target, e.g. {@code @Externalized("${aipersimmon.ddd.messaging.kafka.topic}")} to route
     * an event to it. It is <strong>not</strong> the routing key: routing is per event, from
     * each event's {@code @Externalized} annotation (see {@link ExternalizedRoutes}). An event
     * with no {@code @Externalized} stays LOCAL regardless of this value.
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
         * Upper bound, in milliseconds, on awaiting a broker ack for one send before the
         * relay gives up and retries the row on the next poll. Bounds the single relay
         * thread so one stuck send cannot pin it (and stall all delivery on the instance),
         * and so a wait cannot outlive the relay's {@code lock-at-most-for} and let another
         * instance dispatch the same rows concurrently. Keep
         * {@code outbox.batch-size × this} comfortably below the relay's
         * {@code relay.lock-at-most-for}.
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
         * Whether to run the {@link KafkaIntegrationEventListener} that consumes the
         * topic and republishes events in process. Off by default: a producer-only
         * service does not need it.
         */
        private boolean enabled = false;

        /**
         * Bean name of the database transaction manager the consumer's inbox check and
         * handling run in, so the inbox insert and the handler's writes commit or roll back
         * together. Leave unset when the application has exactly one transaction manager (it
         * is used automatically); set it only when several transaction managers exist and the
         * database one must be chosen explicitly — otherwise startup fails loud rather than
         * risk binding to the wrong manager and losing an event.
         */
        private String transactionManager;

        private final Retry retry = new Retry();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTransactionManager() {
            return transactionManager;
        }

        public void setTransactionManager(String transactionManager) {
            this.transactionManager = transactionManager;
        }

        public Retry getRetry() {
            return retry;
        }

        /**
         * How a failed consume is retried before the record is dead-lettered to
         * {@code <topic>.DLT}. Bounded exponential backoff: a transient fault gets a few
         * spaced retries, then the poison record is quarantined rather than blocking the
         * partition or being silently skipped. A permanent failure (an unknown event
         * type, a malformed payload) is not retried at all — it goes straight to the DLT.
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
