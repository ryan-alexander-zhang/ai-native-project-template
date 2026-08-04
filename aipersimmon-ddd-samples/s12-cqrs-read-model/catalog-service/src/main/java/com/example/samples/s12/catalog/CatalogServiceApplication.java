package com.example.samples.s12.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * S12 catalogue service.
 *
 * <p>Rooted at its own context package rather than at {@code ..s12}, so that the two services in this sample
 * never scan each other's beans. Its configuration is a plain {@code application.yaml}, unlike S10's services
 * — and the difference is a decision, not an inconsistency. S10 boots both of its services in one JVM for its
 * end-to-end module, where only one {@code application.yaml} on the classpath can win; renaming the file is
 * the fix, and it costs every {@code @SpringBootTest} an explicit {@code spring.config.name}, because the test
 * bootstrapper does not go through the application's own builder. S12 has no such module (see the parent POM
 * for why), so the two services never share a classpath and the ordinary shape is the right one.
 */
@SpringBootApplication
public class CatalogServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(CatalogServiceApplication.class, args);
  }
}
