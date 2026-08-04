package com.example.samples.s28;

import com.example.samples.s28.reconciliation.application.ExportSettings;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/** S28: 202, a job resource, and where the synchronous limit actually is. */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(ExportSettings.class)
public class S28Application {

  public static void main(String[] args) {
    SpringApplication.run(S28Application.class, args);
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
