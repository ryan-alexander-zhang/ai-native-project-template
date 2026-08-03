package com.example.samples.s03;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** S3: one command, two subscribers, two different transaction phases. */
@SpringBootApplication
public class S03Application {

  public static void main(String[] args) {
    SpringApplication.run(S03Application.class, args);
  }
}
