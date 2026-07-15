package com.aipersimmon.ddd.messaging.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Kafka integration-event transport, under
 * {@code aipersimmon.ddd.messaging.kafka}.
 */
@ConfigurationProperties("aipersimmon.ddd.messaging.kafka")
public class KafkaMessagingProperties {

    /** Topic that integration events are published to and consumed from. */
    private String topic = "aipersimmon.integration-events";

    private final Consumer consumer = new Consumer();

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public Consumer getConsumer() {
        return consumer;
    }

    /** Settings for the in-process consumer bridge. */
    public static class Consumer {

        /**
         * Whether to run the {@link KafkaIntegrationEventListener} that consumes the
         * topic and republishes events in process. Off by default: a producer-only
         * service does not need it.
         */
        private boolean enabled = false;

        private final Retry retry = new Retry();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
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
