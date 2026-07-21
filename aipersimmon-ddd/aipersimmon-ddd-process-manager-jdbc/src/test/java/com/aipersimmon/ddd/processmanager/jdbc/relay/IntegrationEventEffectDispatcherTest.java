package com.aipersimmon.ddd.processmanager.jdbc.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.fail;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.processmanager.effect.ProcessEffectKind;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import org.junit.jupiter.api.Test;

/**
 * The integration-event effect must be delivered through {@link IntegrationEvents#publishAs} —
 * under the effect's persisted identity, verbatim — not the plain {@code publish}, which mints a
 * fresh event id per call and would give each at-least-once redelivery a different downstream
 * dedupe key (issue-00032).
 */
class IntegrationEventEffectDispatcherTest {

  @EventType(name = "com.example.Sample", version = 1)
  record SampleEvent(String orderId) implements IntegrationEvent {}

  /**
   * Records which entry point the dispatcher used, and fails loudly if it picks {@code publish}.
   */
  static final class RecordingIntegrationEvents implements IntegrationEvents {
    IntegrationEvent publishedAsEvent;
    CommandContext publishedAsContext;

    @Override
    public void publish(IntegrationEvent event, CommandContext context) {
      fail(
          "the effect relay must use publishAs (verbatim persisted identity), never publish (fresh id)");
    }

    @Override
    public void publishAs(IntegrationEvent event, CommandContext context) {
      this.publishedAsEvent = event;
      this.publishedAsContext = context;
    }
  }

  @Test
  void dispatchesThroughPublishAsUnderTheEffectContextVerbatim() {
    RecordingIntegrationEvents events = new RecordingIntegrationEvents();
    IntegrationEventEffectDispatcher dispatcher = new IntegrationEventEffectDispatcher(events);
    SampleEvent event = new SampleEvent("O-1");
    // messageId is the persisted effect id (transitionId#index); publishAs stamps it as the event
    // id.
    CommandContext effectContext = new CommandContext("txn-1#0", "corr-1", "cause-1");

    dispatcher.dispatch(
        new DecodedProcessEffect(
            "txn-1#0",
            new ProcessInstanceId("i-1"),
            ProcessEffectKind.PUBLISH_INTEGRATION_EVENT,
            event),
        effectContext);

    assertSame(event, events.publishedAsEvent, "the decoded event must be published");
    assertSame(
        effectContext,
        events.publishedAsContext,
        "the reconstructed effect context must be passed to publishAs verbatim");
    assertEquals(ProcessEffectKind.PUBLISH_INTEGRATION_EVENT, dispatcher.kind());
  }
}
