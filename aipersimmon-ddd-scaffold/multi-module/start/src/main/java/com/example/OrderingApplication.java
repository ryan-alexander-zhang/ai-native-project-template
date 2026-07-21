package com.example;

import com.aipersimmon.ddd.web.error.ProblemCatalog;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
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
}
