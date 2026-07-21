package com.aipersimmon.ddd.events.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.integration.EventEnvelope;
import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.PayloadApplicationEvent;

class SpringIntegrationEventsTest {

  @EventType(name = "com.example.inventory.SampleIntegration", version = 1)
  record SampleIntegrationEvent(String id) implements IntegrationEvent {}

  @Test
  void publishesEnvelopeCarryingEventAndCausalMetadata() {
    List<Object> captured = new ArrayList<>();
    ApplicationEventPublisher publisher = captured::add;
    IntegrationEvents events = new SpringIntegrationEvents(publisher, "/inventory");

    SampleIntegrationEvent event = new SampleIntegrationEvent("1");
    CommandContext context = new CommandContext("cmd-1", "corr-1", "cause-0");
    events.publish(event, context);

    assertEquals(1, captured.size());
    PayloadApplicationEvent<?> published = (PayloadApplicationEvent<?>) captured.get(0);
    EventEnvelope<?> envelope = (EventEnvelope<?>) published.getPayload();
    assertSame(event, envelope.payload());
    assertEquals("/inventory", envelope.source());
    assertEquals(
        "com.example.inventory.SampleIntegration",
        envelope.type(),
        "the declared @EventType logical type");
    assertEquals("corr-1", envelope.correlationId(), "inherits the command's correlation");
    assertEquals("cmd-1", envelope.causationId(), "caused by the emitting command");
  }
}
