package com.aipersimmon.ddd.observability.otel.autoconfigure;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandInterceptor;
import com.aipersimmon.ddd.observability.ObservabilityAttributes;
import com.aipersimmon.ddd.observability.Tracer;

/**
 * Opens a span around every command so the command dispatch is visible in traces — the
 * auto-instrumentation only sees HTTP/JDBC/Kafka, not the in-house command bus, so without this a
 * trace shows an HTTP span, a gap, then SQL spans with no indication of which command ran.
 *
 * <p>Ordered outermost (further out than {@code LoggingCommandInterceptor} at 0) so the span is
 * active while the rest of the chain — logging, validation, transaction, handler — runs, which lets
 * those logs carry the span's trace id and puts the transaction inside the command span. A thrown
 * exception marks the span as failed before it propagates.
 */
public class TracingCommandInterceptor implements CommandInterceptor {

  /** Ordered outermost, ahead of logging (0), so the span wraps the whole chain. */
  public static final int ORDER = -100;

  private final Tracer tracer;

  public TracingCommandInterceptor(Tracer tracer) {
    this.tracer = tracer;
  }

  @Override
  public <R> R intercept(Command<R> command, CommandContext context, Invocation<R> invocation) {
    String type = command.getClass().getSimpleName();
    try (Tracer.SpanScope span = tracer.startSpan("command " + type)) {
      span.attribute(ObservabilityAttributes.COMMAND_TYPE, type)
          .attribute(ObservabilityAttributes.MESSAGE_ID, context.messageId())
          .attribute(ObservabilityAttributes.CORRELATION_ID, context.correlationId())
          .attribute(ObservabilityAttributes.CAUSATION_ID, context.causationId());
      try {
        return invocation.proceed();
      } catch (RuntimeException e) {
        span.error(e);
        throw e;
      }
    }
  }

  @Override
  public int order() {
    return ORDER;
  }
}
