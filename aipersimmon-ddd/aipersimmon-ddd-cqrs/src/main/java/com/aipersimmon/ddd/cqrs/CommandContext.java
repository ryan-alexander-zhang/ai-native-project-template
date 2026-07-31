package com.aipersimmon.ddd.cqrs;

import com.aipersimmon.ddd.tenancy.TenantId;
import com.aipersimmon.ddd.tenancy.Tenants;

/**
 * The metadata that travels alongside a command as it is dispatched — never inside the command
 * payload. It carries the owning tenant, the identity of this command message, and the causal chain
 * it belongs to, so logs, traces, and any integration events emitted while handling the command can
 * be correlated back to what triggered it — and stay isolated to the right tenant.
 *
 * <ul>
 *   <li>{@code tenantId} — the owning tenant (the data-isolation boundary), as a {@link TenantId}
 *       rather than a string. Never null: single-tenant deployments use the sentinel {@link
 *       Tenants#ROOT} (N=1 multi-tenancy). The type is the guard — a context cannot be built from
 *       an arbitrary string, only from a value that passed {@link Tenants#of} (which refuses the
 *       reserved {@code __} prefix, so a caller cannot casually name a framework sentinel) or from
 *       {@link Tenants#fromValue}, the explicit "I am at a trust boundary, this value was already
 *       vetted" act. Fabricating a tenant therefore requires performing that act where a reviewer
 *       can see it, instead of passing any string that survives an isBlank check.
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
 * <p>An inbound integration event becomes the triggering cause of a command through {@code
 * InboundEvents.commandContext(envelope)} in {@code aipersimmon-ddd-application}. That conversion
 * deliberately does not live here: this module is the write-side core and must not know the wire
 * format, so {@code cqrs} depends only on {@code core} and {@code tenancy}.
 *
 * <p>Framework-free and immutable. Ids are minted by the {@link CommandBus}, not by this type, and
 * the tenant is seeded by the bus from the ambient {@code TenantContext}; use {@link
 * #root(TenantId, String)} and {@link #deriveChild(String)} to build the chain from a bus-supplied
 * id. There is deliberately no tenant-defaulting overload: the owning tenant is always an explicit
 * choice, made at the trusted boundary that mints the context (the bus reads it from the ambient
 * tenant; a genuinely tenant-less system path passes {@link Tenants#ROOT} by name).
 */
public record CommandContext(
    TenantId tenantId, String messageId, String correlationId, String causationId) {

  public CommandContext {
    if (tenantId == null) {
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
   * A root context under an explicit tenant, for a command with no upstream cause (for example an
   * HTTP request whose tenant was resolved at the edge). Correlation is seeded to the command's own
   * id.
   *
   * @param tenantId the owning tenant
   * @param messageId the bus-minted id for this command
   */
  public static CommandContext root(TenantId tenantId, String messageId) {
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
}
