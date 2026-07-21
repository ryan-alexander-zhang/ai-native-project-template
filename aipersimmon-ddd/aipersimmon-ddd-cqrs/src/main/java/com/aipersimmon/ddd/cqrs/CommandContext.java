package com.aipersimmon.ddd.cqrs;

import com.aipersimmon.ddd.integration.EventEnvelope;

/**
 * The metadata that travels alongside a command as it is dispatched — never inside the command
 * payload. It carries the identity of this command message and the causal chain it belongs to, so
 * logs, traces, and any integration events emitted while handling the command can be correlated
 * back to what triggered it.
 *
 * <ul>
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
 * <p>Framework-free and immutable. Ids are minted by the {@link CommandBus}, not by this type, so
 * it stays a pure value; use {@link #root(String)} and {@link #deriveChild(String)} to build the
 * chain from a bus-supplied id.
 */
public record CommandContext(String messageId, String correlationId, String causationId) {

  public CommandContext {
    if (messageId == null || messageId.isBlank()) {
      throw new IllegalArgumentException("messageId required");
    }
    if (correlationId == null || correlationId.isBlank()) {
      throw new IllegalArgumentException("correlationId required");
    }
    // causationId is null for a root command.
  }

  /**
   * A root context for a command with no upstream cause (for example an HTTP request or a scheduled
   * job): correlationId is seeded to the command's own id.
   *
   * @param messageId the bus-minted id for this command
   */
  public static CommandContext root(String messageId) {
    return new CommandContext(messageId, messageId, null);
  }

  /**
   * The context for a message caused by this one — a follow-up command dispatched while handling
   * this command, or an integration event emitted from it. The child keeps this context's
   * correlation, and records this message as its cause.
   *
   * @param childMessageId the bus-minted id for the caused message
   */
  public CommandContext deriveChild(String childMessageId) {
    return new CommandContext(childMessageId, correlationId, messageId);
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
    return new CommandContext(envelope.eventId(), envelope.correlationId(), envelope.causationId());
  }
}
