package com.example.samples.s20;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** S20: what a list endpoint's contract has to say. */
@SpringBootApplication
public class S20Application {

  public static void main(String[] args) {
    SpringApplication.run(S20Application.class, args);
  }

  /** The sort key's source. A bean so a test can freeze it; UTC so the column never guesses. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
