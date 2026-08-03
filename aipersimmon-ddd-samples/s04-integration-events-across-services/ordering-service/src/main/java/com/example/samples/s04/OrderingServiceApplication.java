package com.example.samples.s04;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The publishing deployable.
 *
 * <p>{@code @EnableScheduling} is what lets the outbox relay poll. Without it the rows are written and
 * nothing ever ships them — a failure with no error message, so it is worth knowing that this one
 * annotation is load-bearing.
 */
@SpringBootApplication
@EnableScheduling
public class OrderingServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(OrderingServiceApplication.class, args);
  }
}
