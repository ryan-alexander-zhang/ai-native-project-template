package com.example.samples.s23;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** One deployable, two contexts, three sets of migrations. */
@SpringBootApplication
public class S23Application {

  public static void main(String[] args) {
    SpringApplication.run(S23Application.class, args);
  }
}
