package com.aipersimmon.ddd.cqrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.integration.EventEnvelope;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CommandContextTest {

  record ThingImported(String id) implements IntegrationEvent {}

  @Test
  void rejectsNullMessageId() {
    assertThrows(IllegalArgumentException.class, () -> new CommandContext(null, "corr", null));
  }

  @Test
  void rejectsBlankMessageId() {
    assertThrows(IllegalArgumentException.class, () -> new CommandContext(" ", "corr", null));
  }

  @Test
  void rejectsNullCorrelationId() {
    assertThrows(IllegalArgumentException.class, () -> new CommandContext("msg", null, null));
  }

  @Test
  void rejectsBlankCorrelationId() {
    assertThrows(IllegalArgumentException.class, () -> new CommandContext("msg", " ", null));
  }

  @Test
  void acceptsNullCausationForARootCommand() {
    CommandContext ctx = new CommandContext("msg", "corr", null);

    assertEquals("msg", ctx.messageId());
    assertEquals("corr", ctx.correlationId());
    assertNull(ctx.causationId());
  }

  @Test
  void rootSeedsCorrelationToItsOwnIdWithNoCause() {
    CommandContext ctx = CommandContext.root("cmd-1");

    assertEquals("cmd-1", ctx.messageId());
    assertEquals("cmd-1", ctx.correlationId());
    assertNull(ctx.causationId());
  }

  @Test
  void deriveChildKeepsCorrelationAndRecordsThisAsCause() {
    CommandContext parent = CommandContext.root("cmd-1");

    CommandContext child = parent.deriveChild("cmd-2");

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
            "corr-3",
            "upstream-cause",
            new ThingImported("t-1"));

    CommandContext ctx = CommandContext.of(envelope);

    assertEquals("evt-9", ctx.messageId());
    assertEquals("corr-3", ctx.correlationId());
    assertEquals("upstream-cause", ctx.causationId());
  }
}
