package com.example.samples.s17;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** No web tier: this sample is the write path and nothing else. */
@SpringBootApplication
public class S17Application {

  public static void main(String[] args) {
    SpringApplication.run(S17Application.class, args);
  }
}
