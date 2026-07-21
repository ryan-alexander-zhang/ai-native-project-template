package com.aipersimmon.ddd.events.spring;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.application.IntegrationEvents;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Proves that adding this starter auto-configures Spring-backed publishers for both event kinds
 * (the outbox starter is not on this module's classpath, so the in-process integration publisher
 * applies).
 */
@SpringBootTest(classes = AutoConfigurationWiringTest.TestApp.class)
class AutoConfigurationWiringTest {

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class TestApp {}

  @Autowired DomainEvents domainEvents;
  @Autowired IntegrationEvents integrationEvents;

  @Test
  void autoConfiguresSpringDomainEvents() {
    assertInstanceOf(SpringDomainEvents.class, domainEvents);
  }

  @Test
  void autoConfiguresSpringIntegrationEventsWhenOutboxAbsent() {
    assertInstanceOf(SpringIntegrationEvents.class, integrationEvents);
  }
}
