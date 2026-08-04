package com.example.samples.s07;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/** S7: our side of an integration with a system nobody here controls. */
@SpringBootApplication
@EnableScheduling
public class PaymentServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(PaymentServiceApplication.class, args);
  }

  /**
   * A bean, so a test can decide what "stuck for ten minutes" means instead of waiting ten minutes. The
   * library does not provide one — it takes a {@code Clock} where it needs one and leaves the choice of
   * time source to the application, which is why every sample that reasons about elapsed time declares
   * this.
   */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
