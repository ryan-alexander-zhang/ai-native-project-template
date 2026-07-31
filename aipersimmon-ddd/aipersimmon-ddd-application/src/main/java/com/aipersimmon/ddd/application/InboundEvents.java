package com.aipersimmon.ddd.application;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.integration.EventEnvelope;
import com.aipersimmon.ddd.tenancy.Tenants;

/**
 * How an inbound integration event becomes the cause of the command it triggers.
 *
 * <p>An anti-corruption adapter that receives an {@link EventEnvelope} and dispatches a command
 * passes the result as the command's context:
 *
 * <pre>{@code
 * commandBus.send(new ReserveStock(orderId, lines), InboundEvents.commandContext(envelope));
 * }</pre>
 *
 * <p>This is the one place that conversion is written; inbound adapters do not each re-map the
 * envelope's fields. It lives in the application module rather than on either of the two types it
 * bridges, because translating an inbound event into a command is an application-layer
 * responsibility — and because putting it on either side would couple two modules that should not
 * know each other: {@code cqrs} is the write-side core and must not know the wire format, while
 * {@code integration} is a dependency-free root that an events-only application must be able to use
 * without pulling in the command bus.
 */
public final class InboundEvents {

  private InboundEvents() {}

  /**
   * The causal context for a command triggered by an inbound integration event. The event's id
   * becomes the context's {@code messageId}, so the dispatched command — built with {@link
   * CommandContext#deriveChild(String)} by the bus — records the event as its causation and
   * inherits its correlation and tenant.
   */
  public static CommandContext commandContext(EventEnvelope<?> envelope) {
    // Tenants.fromValue, not Tenants.of: the envelope's tenant is a persisted wire value that may
    // legitimately be a framework sentinel, and re-adopting it here IS the trust-boundary act —
    // the consuming bridge already vetted the envelope before handing it over.
    return new CommandContext(
        Tenants.fromValue(envelope.tenantId()),
        envelope.eventId(),
        envelope.correlationId(),
        envelope.causationId());
  }
}
