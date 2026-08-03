package com.example.samples.s06;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The callee.
 *
 * <p>No {@code @EnableScheduling}: there is no relay to poll, no retention to clean and no deadline to
 * chase, because there is nothing stored. A decision service's whole lifecycle is a request.
 */
@SpringBootApplication
public class RiskServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(RiskServiceApplication.class, args);
  }
}
