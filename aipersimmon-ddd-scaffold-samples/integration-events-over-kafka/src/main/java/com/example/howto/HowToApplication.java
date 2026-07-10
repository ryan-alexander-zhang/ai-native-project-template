package com.example.howto;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Boots the how-to. The outbox writer and relay, the Kafka dispatcher, and the
 * consumer bridge all auto-configure themselves; the bridge is on because
 * {@code aipersimmon.ddd.messaging.kafka.consumer.enabled=true}.
 */
@SpringBootApplication
public class HowToApplication {

    public static void main(String[] args) {
        SpringApplication.run(HowToApplication.class, args);
    }
}
