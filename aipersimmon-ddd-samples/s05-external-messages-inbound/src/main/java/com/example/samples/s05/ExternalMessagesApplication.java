package com.example.samples.s05;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The mirroring service.
 *
 * <p>{@code @EnableScheduling} is here for the inbox's retention cleanup. Retention has a specific
 * meaning for a foreign integration: the key must be remembered for longer than the longest redelivery
 * the <em>upstream</em> can produce, and that number is theirs, not ours — which makes it a question to
 * ask them rather than a default to accept.
 */
@SpringBootApplication
@EnableScheduling
public class ExternalMessagesApplication {

  public static void main(String[] args) {
    SpringApplication.run(ExternalMessagesApplication.class, args);
  }
}
