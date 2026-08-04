package com.example.samples.s09;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/** S9: orchestration and compensation over three aggregates. */
@SpringBootApplication
@EnableScheduling
public class S09Application {

  public static void main(String[] args) {
    SpringApplication.run(S09Application.class, args);
  }

  /**
   * The participants' clock. Note the flow's own decisions never use it — a {@code ProcessDefinition}
   * receives {@code now} in its context precisely so it stays deterministic and replayable.
   */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
