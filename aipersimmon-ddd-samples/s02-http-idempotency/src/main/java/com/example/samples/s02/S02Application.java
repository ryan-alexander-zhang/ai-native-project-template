package com.example.samples.s02;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** S2: the three edge protections, on a shared Redis store. */
@SpringBootApplication
public class S02Application {

  public static void main(String[] args) {
    SpringApplication.run(S02Application.class, args);
  }
}
