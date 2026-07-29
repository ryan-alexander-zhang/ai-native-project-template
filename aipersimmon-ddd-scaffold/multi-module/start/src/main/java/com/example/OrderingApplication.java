package com.example;

import com.aipersimmon.ddd.web.error.ProblemCatalog;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Boots the ordering context, wiring every layer into one deployable. */
@SpringBootApplication
public class OrderingApplication {

  public static void main(String[] args) {
    SpringApplication.run(OrderingApplication.class, args);
  }

  /** Registers the ordering problem-type overrides; unlisted codes ride their category family. */
  @Bean
  ProblemCatalog orderingProblemCatalog() {
    return new OrderingProblemCatalog();
  }

  /**
   * The application's clock. Injected rather than reached for, so anything that stamps a time can
   * be driven from a test without sleeping — {@code FulfilmentTrigger} computes the reservation
   * deadline it publishes from this.
   *
   * <p>{@link ConditionalOnMissingBean} so a test can substitute a fixed clock by declaring its
   * own, and so a future library module that ships one does not collide with this.
   */
  @Bean
  @ConditionalOnMissingBean(Clock.class)
  Clock clock() {
    return Clock.systemUTC();
  }
}
