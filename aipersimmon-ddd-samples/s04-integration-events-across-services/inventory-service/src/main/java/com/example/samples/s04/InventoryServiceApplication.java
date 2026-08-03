package com.example.samples.s04;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The consuming deployable.
 *
 * <p>{@code @EnableScheduling} is here for the inbox's retention cleanup, not for a relay: this service
 * publishes nothing. Retention matters more than it looks — a handled key must be remembered for longer
 * than the longest redelivery that can still arrive, or a very late duplicate is processed twice.
 */
@SpringBootApplication
@EnableScheduling
public class InventoryServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(InventoryServiceApplication.class, args);
  }
}
