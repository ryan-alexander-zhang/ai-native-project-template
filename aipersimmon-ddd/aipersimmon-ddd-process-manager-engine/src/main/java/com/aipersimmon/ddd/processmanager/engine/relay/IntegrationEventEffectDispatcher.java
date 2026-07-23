package com.aipersimmon.ddd.processmanager.jdbc.relay;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.processmanager.effect.ProcessEffectKind;

/**
 * Delivers a {@code PublishIntegrationEvent} effect through {@link
 * IntegrationEvents#publishAs(com.aipersimmon.ddd.integration.IntegrationEvent, CommandContext)} —
 * under the effect's persisted identity, verbatim, so the outbound event id equals the effect id
 * and every at-least-once redelivery of the same staged effect reaches the downstream context under
 * the same event id. That stable id is what lets the target's inbox dedupe redeliveries; publishing
 * through the plain {@code publish} path would mint a fresh id per redelivery and defeat that
 * dedupe. Used to reach a bounded context deployed as a separate service.
 */
public final class IntegrationEventEffectDispatcher implements ProcessEffectDispatcher {

  private final IntegrationEvents integrationEvents;

  public IntegrationEventEffectDispatcher(IntegrationEvents integrationEvents) {
    this.integrationEvents = integrationEvents;
  }

  @Override
  public ProcessEffectKind kind() {
    return ProcessEffectKind.PUBLISH_INTEGRATION_EVENT;
  }

  @Override
  public void dispatch(DecodedProcessEffect effect, CommandContext context) {
    integrationEvents.publishAs((IntegrationEvent) effect.payload(), context);
  }
}
