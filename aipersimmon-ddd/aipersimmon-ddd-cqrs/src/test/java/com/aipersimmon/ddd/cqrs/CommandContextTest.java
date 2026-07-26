package com.aipersimmon.ddd.cqrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.integration.EventEnvelope;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.tenancy.Tenants;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CommandContextTest {

  record ThingImported(String id) implements IntegrationEvent {}

  @Test
  void rejectsNullTenantId() {
    assertThrows(
        IllegalArgumentException.class, () -> new CommandContext(null, "msg", "corr", null));
  }

  @Test
  void rejectsBlankTenantId() {
    assertThrows(
        IllegalArgumentException.class, () -> new CommandContext(" ", "msg", "corr", null));
  }

  @Test
  void rejectsNullMessageId() {
    assertThrows(
        IllegalArgumentException.class, () -> new CommandContext("t-1", null, "corr", null));
  }

  @Test
  void rejectsBlankMessageId() {
    assertThrows(
        IllegalArgumentException.class, () -> new CommandContext("t-1", " ", "corr", null));
  }

  @Test
  void rejectsNullCorrelationId() {
    assertThrows(
        IllegalArgumentException.class, () -> new CommandContext("t-1", "msg", null, null));
  }

  @Test
  void rejectsBlankCorrelationId() {
    assertThrows(IllegalArgumentException.class, () -> new CommandContext("t-1", "msg", " ", null));
  }

  @Test
  void acceptsNullCausationForARootCommand() {
    CommandContext ctx = new CommandContext("t-1", "msg", "corr", null);

    assertEquals("t-1", ctx.tenantId());
    assertEquals("msg", ctx.messageId());
    assertEquals("corr", ctx.correlationId());
    assertNull(ctx.causationId());
  }

  @Test
  void rootUnderTheSentinelTenantSeedsCorrelationToItsOwnId() {
    CommandContext ctx = CommandContext.root(Tenants.ROOT.value(), "cmd-1");

    assertEquals(Tenants.ROOT.value(), ctx.tenantId());
    assertEquals("cmd-1", ctx.messageId());
    assertEquals("cmd-1", ctx.correlationId());
    assertNull(ctx.causationId());
  }

  @Test
  void rootUnderAnExplicitTenantCarriesThatTenant() {
    CommandContext ctx = CommandContext.root("acme", "cmd-1");

    assertEquals("acme", ctx.tenantId());
    assertEquals("cmd-1", ctx.messageId());
    assertEquals("cmd-1", ctx.correlationId());
  }

  @Test
  void deriveChildKeepsTenantAndCorrelationAndRecordsThisAsCause() {
    CommandContext parent = CommandContext.root("acme", "cmd-1");

    CommandContext child = parent.deriveChild("cmd-2");

    assertEquals("acme", child.tenantId());
    assertEquals("cmd-2", child.messageId());
    assertEquals("cmd-1", child.correlationId());
    assertEquals("cmd-1", child.causationId());
  }

  @Test
  void ofEnvelopeCopiesIdCorrelationAndCausation() {
    EventEnvelope<ThingImported> envelope =
        new EventEnvelope<>(
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

    CommandContext ctx = CommandContext.of(envelope);

    assertEquals("acme", ctx.tenantId());
    assertEquals("evt-9", ctx.messageId());
    assertEquals("corr-3", ctx.correlationId());
    assertEquals("upstream-cause", ctx.causationId());
  }
}
