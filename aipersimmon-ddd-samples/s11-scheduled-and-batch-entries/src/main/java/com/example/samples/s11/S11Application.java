package com.example.samples.s11;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/** S11: entries that are not HTTP. */
@SpringBootApplication
@EnableScheduling
public class S11Application {

  public static void main(String[] args) {
    SpringApplication.run(S11Application.class, args);
  }

  /** A bean, so a test can decide when "overdue" is instead of sleeping through it. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
