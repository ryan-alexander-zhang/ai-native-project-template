package com.example.samples.s21;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The consuming deployable.
 *
 * <p>{@code @EnableScheduling} is for the inbox's retention cleanup. Retention is a contract-evolution
 * concern too: a handled key must be remembered for longer than the longest redelivery that can still
 * arrive, and "longest" is set by the same three things that decide when a revision may be deleted.
 */
@SpringBootApplication
@EnableScheduling
public class InventoryServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(InventoryServiceApplication.class, args);
  }
}
