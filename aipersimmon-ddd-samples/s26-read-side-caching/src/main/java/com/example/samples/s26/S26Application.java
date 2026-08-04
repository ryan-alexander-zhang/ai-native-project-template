package com.example.samples.s26;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** One read, answered three ways: from the source, from a cache, and from a projection. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class S26Application {

  public static void main(String[] args) {
    SpringApplication.run(S26Application.class, args);
  }
}
