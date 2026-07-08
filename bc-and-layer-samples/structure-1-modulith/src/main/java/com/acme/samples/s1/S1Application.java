package com.acme.samples.s1;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Structure 1 — modular monolith with logical bounded contexts.
 *
 * <p>Each direct sub-package of this one ({@code ordering}, {@code inventory}) is
 * a Spring Modulith application module (a bounded context). Layers live as
 * sub-packages inside each and are checked by ArchUnit/Modulith at test time.
 * Cross-context integration events travel through the Spring Modulith event
 * publication registry (a transactional outbox) and are externalized to Kafka.
 */
@SpringBootApplication
@EnableScheduling
public class S1Application {
    public static void main(String[] args) {
        SpringApplication.run(S1Application.class, args);
    }
}
