package com.example.samples.s10.points;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * S10 points service: the participant.
 *
 * <p>Its configuration is {@code points-service.yaml} rather than {@code application.yaml}, for the reason
 * spelled out in {@code AccountServiceApplication}: the two services share a classpath in the end-to-end
 * tests, and only one {@code application.yaml} can win.
 */
@SpringBootApplication
public class PointsServiceApplication {

  /** The one place that knows how this service is configured. */
  public static SpringApplicationBuilder application() {
    return new SpringApplicationBuilder(PointsServiceApplication.class)
        .properties("spring.config.name=points-service");
  }

  public static void main(String[] args) {
    application().run(args);
  }
}
