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

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
