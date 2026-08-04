package com.example.samples.s24;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * The composition root, and the only class that legitimately sees every context.
 *
 * <p>It sits directly in the base package rather than in a sub-package, which is not cosmetic: the library's isolation
 * rule treats every immediate sub-package as a context and skips classes that sit directly in the base package. A
 * composition root in {@code s24.boot} would be a context called {@code boot} that depends on everything, and the rule
 * would be right to complain.
 */
@SpringBootApplication
public class S24Application {

  public static void main(String[] args) {
    SpringApplication.run(S24Application.class, args);
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
