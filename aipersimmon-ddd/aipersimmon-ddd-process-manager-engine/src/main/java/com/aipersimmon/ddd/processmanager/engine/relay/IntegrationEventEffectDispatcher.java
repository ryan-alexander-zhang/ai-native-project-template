package com.aipersimmon.ddd.processmanager.engine.relay;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.processmanager.effect.ProcessEffectKind;
import com.aipersimmon.ddd.processmanager.engine.runtime.ProcessUnitOfWork;

/**
 * Delivers a {@code PublishIntegrationEvent} effect through {@link
 * IntegrationEvents#publishAs(com.aipersimmon.ddd.integration.IntegrationEvent, CommandContext)} —
 * under the effect's persisted identity, verbatim, so the outbound event id equals the effect id
 * and every at-least-once redelivery of the same staged effect reaches the downstream context under
 * the same event id. That stable id is what lets the target's inbox dedupe redeliveries; publishing
 * through the plain {@code publish} path would mint a fresh id per redelivery and defeat that
 * dedupe. Used to reach a bounded context deployed as a separate service.
 *
 * <p>The publish runs inside its own {@link ProcessUnitOfWork} transaction. The relay dispatches
 * outside the advance transaction, and the default {@code IntegrationEvents} is the transactional
 * outbox writer, which refuses to write a row without an active transaction. The state change the
 * row belongs to — the staged effect — already committed with the advance, so a transaction around
 * the write alone is the whole guarantee here: the row commits, or the effect is retried. A crash
 * between that commit and the relay's delivered mark redelivers under the same event id, which the
 * outbox collapses onto the existing row.
 */
public final class IntegrationEventEffectDispatcher implements ProcessEffectDispatcher {

  private final IntegrationEvents integrationEvents;
  private final ProcessUnitOfWork unitOfWork;

  public IntegrationEventEffectDispatcher(
      IntegrationEvents integrationEvents, ProcessUnitOfWork unitOfWork) {
    this.integrationEvents = integrationEvents;
    this.unitOfWork = unitOfWork;
  }

  @Override
  public ProcessEffectKind kind() {
    return ProcessEffectKind.PUBLISH_INTEGRATION_EVENT;
  }

  @Override
  public void dispatch(DecodedProcessEffect effect, CommandContext context) {
    unitOfWork.execute(
        () -> {
          integrationEvents.publishAs((IntegrationEvent) effect.payload(), context);
          return null;
        });
  }
}
