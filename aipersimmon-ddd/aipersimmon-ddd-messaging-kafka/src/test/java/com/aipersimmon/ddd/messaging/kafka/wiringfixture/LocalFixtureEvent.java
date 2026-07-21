package com.aipersimmon.ddd.messaging.kafka.wiringfixture;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/** Fixture: a LOCAL event (no {@code @Externalized}) that must never add a subscription. */
@EventType(name = "com.example.wiring.Local", version = 1)
public record LocalFixtureEvent(String id) implements IntegrationEvent {

  @Override
  public String subject() {
    return id;
  }
}
