package com.aipersimmon.ddd.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntegrationEventsTest {

  private record SampleEvent(String id) implements IntegrationEvent {}

  @Test
  void publish_isTheOnlyRequiredOperation() {
    List<IntegrationEvent> published = new ArrayList<>();
    IntegrationEvents events = (event, context) -> published.add(event);

    SampleEvent event = new SampleEvent("e-1");
    events.publish(event, CommandContext.root("c-1"));

    assertEquals(List.of(event), published);
  }

  @Test
  void publishAs_isUnsupportedByDefault() {
    IntegrationEvents events = (event, context) -> {};

    UnsupportedOperationException ex =
        assertThrows(
            UnsupportedOperationException.class,
            () -> events.publishAs(new SampleEvent("e-1"), CommandContext.root("c-1")));
    assertEquals(
        "this IntegrationEvents transport does not support staged (publishAs) publication",
        ex.getMessage());
  }
}
