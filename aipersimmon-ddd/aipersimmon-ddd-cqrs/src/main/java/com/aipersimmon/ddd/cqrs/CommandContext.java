package com.aipersimmon.ddd.cqrs;

import com.aipersimmon.ddd.integration.EventEnvelope;
import com.aipersimmon.ddd.tenancy.Tenants;

/**
 * The metadata that travels alongside a command as it is dispatched — never inside the command
 * payload. It carries the owning tenant, the identity of this command message, and the causal chain
 * it belongs to, so logs, traces, and any integration events emitted while handling the command can
 * be correlated back to what triggered it — and stay isolated to the right tenant.
 *
 * <ul>
 *   <li>{@code tenantId} — the owning tenant (the data-isolation boundary). Never blank: single
 *       tenant deployments use the sentinel {@link Tenants#ROOT} (N=1 multi-tenancy).
 *   <li>{@code messageId} — this command's own id, unique per dispatch.
 *   <li>{@code correlationId} — stable across the whole flow: every command and event descending
 *       from one root shares it. A root command's correlationId equals its own messageId.
 *   <li>{@code causationId} — the id of the message that directly caused this one (the inbound
 *       integration event, or the parent command). {@code null} for a root command with no upstream
 *       cause.
 * </ul>
 *
 * <p>Distributed-trace identity is carried out of band by the OpenTelemetry context (a W3C {@code
 * traceparent}), not by this value: it needs no trace-id field.
 *
 * <p>Framework-free and immutable. Ids are minted by the {@link CommandBus}, not by this type, and
 * the tenant is seeded by the bus from the ambient {@code TenantContext}; use {@link #root(String,
 * String)} / {@link #root(String)} and {@link #deriveChild(String)} to build the chain from a
 * bus-supplied id.
 */
public record CommandContext(
    String tenantId, String messageId, String correlationId, String causationId) {

  public CommandContext {
    if (tenantId == null || tenantId.isBlank()) {
      throw new IllegalArgumentException("tenantId required");
    }
    if (messageId == null || messageId.isBlank()) {
      throw new IllegalArgumentException("messageId required");
    }
    if (correlationId == null || correlationId.isBlank()) {
      throw new IllegalArgumentException("correlationId required");
    }
    // causationId is null for a root command.
  }

  /**
   * A root context under the sentinel {@link Tenants#ROOT} tenant, for a command with no resolved
   * tenant (single-tenant deployments) and no upstream cause. Correlation is seeded to the
   * command's own id.
   *
   * @param messageId the bus-minted id for this command
   */
  public static CommandContext root(String messageId) {
    return root(Tenants.ROOT.value(), messageId);
  }

  /**
   * A root context under an explicit tenant, for a command with no upstream cause (for example an
   * HTTP request whose tenant was resolved at the edge). Correlation is seeded to the command's own
   * id.
   *
   * @param tenantId the owning tenant
   * @param messageId the bus-minted id for this command
   */
  public static CommandContext root(String tenantId, String messageId) {
    return new CommandContext(tenantId, messageId, messageId, null);
  }

  /**
   * The context for a message caused by this one — a follow-up command dispatched while handling
   * this command, or an integration event emitted from it. The child keeps this context's tenant
   * and correlation, and records this message as its cause.
   *
   * @param childMessageId the bus-minted id for the caused message
   */
  public CommandContext deriveChild(String childMessageId) {
    return new CommandContext(tenantId, childMessageId, correlationId, messageId);
  }

  /**
   * The context of an inbound integration event, to pass as the triggering cause when an
   * anti-corruption adapter translates the event into a command ({@code commandBus.send(command,
   * CommandContext.of(envelope))}). The event's id becomes this context's {@code messageId}, so the
   * dispatched command records the event as its causation and inherits its correlation. This is the
   * one place inbound adapters convert an {@link EventEnvelope} to a context — they do not each
   * re-map its fields.
   */
  public static CommandContext of(EventEnvelope<?> envelope) {
    return new CommandContext(
        envelope.tenantId(), envelope.eventId(), envelope.correlationId(), envelope.causationId());
  }
}
