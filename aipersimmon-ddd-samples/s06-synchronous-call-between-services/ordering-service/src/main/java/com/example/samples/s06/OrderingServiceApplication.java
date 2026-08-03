package com.example.samples.s06;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The caller.
 *
 * <p>No {@code @EnableScheduling}, because there is nothing to poll: a synchronous call leaves no durable
 * trace to drain. That is the whole trade — no relay, no inbox, no reconciliation job, and no second
 * chance either.
 */
@SpringBootApplication
public class OrderingServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(OrderingServiceApplication.class, args);
  }
}
