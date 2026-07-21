package com.aipersimmon.ddd.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class IntegrationEventExceptionsTest {

  @Test
  void unknownIntegrationEvent_spellsOutTheTypeVersionAndDeadLetterGuidance() {
    UnknownIntegrationEventException ex =
        new UnknownIntegrationEventException("com.example.ordering.OrderPlaced", 3);

    assertEquals(
        "no integration event registered for type 'com.example.ordering.OrderPlaced' version 3; "
            + "the consumer has no local class for this contract and does not guess by class name — "
            + "dead-letter the message",
        ex.getMessage());
  }

  @Test
  void malformedIntegrationEvent_passesTheMessageThrough() {
    MalformedIntegrationEventException ex =
        new MalformedIntegrationEventException("missing CloudEvents id");

    assertEquals("missing CloudEvents id", ex.getMessage());
  }
}
