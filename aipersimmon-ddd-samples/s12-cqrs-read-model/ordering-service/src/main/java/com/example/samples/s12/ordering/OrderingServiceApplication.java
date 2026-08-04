package com.example.samples.s12.ordering;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * S12 ordering service: owns the orders, the projection, and its own replica of the catalogue's names.
 *
 * <p>Rooted at its own context package so the two services in this sample never scan each other's beans. See
 * {@code CatalogServiceApplication} for why the configuration file is a plain {@code application.yaml} here
 * and is not in S10.
 */
@SpringBootApplication
public class OrderingServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(OrderingServiceApplication.class, args);
  }

  /**
   * The application's clock — and in this service it is not optional, for a reason worth knowing.
   *
   * <p>The library takes a {@code Clock} where it needs one and leaves the time source to the application.
   * Each framework component also contributes its <em>own</em> named clock ({@code outboxClock}, {@code
   * inboxClock}) rather than backing off when another has registered one, which is deliberate — but it means a
   * service carrying two components has two beans of type {@code Clock} and no primary, so injecting {@code
   * Clock} by type alone fails to start. Declaring one here and naming the injection points {@code clock}
   * resolves it by name. Measured: without this bean the context refuses with "expected single matching bean
   * but found 2: outboxClock, inboxClock".
   */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
