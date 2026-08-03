package com.example.samples.s21;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The publishing deployable.
 *
 * <p>{@code @EnableScheduling} is what lets the outbox relay poll — load-bearing, and doubly so here:
 * the relay is what drains rows written at retired revisions, and until they are drained the retired
 * revision is still being published however thoroughly it has been deleted from the code.
 */
@SpringBootApplication
@EnableScheduling
public class OrderingServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(OrderingServiceApplication.class, args);
  }
}
