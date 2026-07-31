package com.aipersimmon.ddd.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.integration.EventEnvelope;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.tenancy.Tenants;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * An inbound event's identity has to survive the hop into the command it triggers, or the causal
 * chain breaks at every context boundary: the command would look like a fresh root and nothing
 * downstream could be traced back to the event that caused it.
 */
class InboundEventsTest {

  record ThingImported(String id) implements IntegrationEvent {}

  private static EventEnvelope<ThingImported> envelope() {
    return new EventEnvelope<>(
        "evt-9",
        "/test",
        "ThingImported",
        1,
        Instant.EPOCH,
        "subj-1",
        "acme",
        "corr-3",
        "upstream-cause",
        new ThingImported("t-1"));
  }

  @Test
  void theEnvelopeSTenantIdCorrelationAndCausationAreCarriedOver() {
    CommandContext cause = InboundEvents.commandContext(envelope());

    assertEquals(
        Tenants.of("acme"), cause.tenantId(), "the event's tenant owns the work it triggers");
    assertEquals("evt-9", cause.messageId(), "the event's id becomes the cause's message id");
    assertEquals("corr-3", cause.correlationId());
    assertEquals("upstream-cause", cause.causationId());
  }

  @Test
  void aCommandDispatchedFromItRecordsTheEventAsItsCausation() {
    CommandContext command = InboundEvents.commandContext(envelope()).deriveChild("cmd-1");

    assertEquals("corr-3", command.correlationId(), "one correlation spans the whole flow");
    assertEquals("evt-9", command.causationId(), "the event is what directly caused this command");
    assertEquals(Tenants.of("acme"), command.tenantId());
  }
}
