package com.example.samples.s01;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** S1: one HTTP write and one HTTP read, from controller to committed row. */
@SpringBootApplication
public class S01Application {

  public static void main(String[] args) {
    SpringApplication.run(S01Application.class, args);
  }
}
