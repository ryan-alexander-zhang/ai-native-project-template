package com.aipersimmon.ddd.observability;

/**
 * Opens spans around the self-written domain spine that library auto-instrumentation does not
 * recognise — command/query dispatch, domain-event handling, inbound ACL, and process-manager
 * advance. It exists so modules that must stay framework-free (notably {@code
 * aipersimmon-ddd-process-manager-jdbc}) can emit spans without a compile dependency on
 * OpenTelemetry; Spring-side spans are created directly against OTEL in the optional otel module
 * and do not need this SPI.
 *
 * <p>A span is opened as a child of the currently active context, so calls nest along the
 * synchronous call stack. The default {@link NoOpTracer} returns inert scopes.
 */
public interface Tracer {

  /**
   * Start a span as a child of the active context.
   *
   * @param name the span name (e.g. {@code "process.advance OrderFulfilment"})
   * @return a scope that keeps the span active until closed; never {@code null}
   */
  SpanScope startSpan(String name);

  /** An active span; set attributes/errors on it, then {@link #close()} to end it. */
  interface SpanScope extends AutoCloseable {

    /**
     * Set a string attribute (see {@link ObservabilityAttributes} for the catalog). Null values are
     * ignored. Returns {@code this} for chaining.
     */
    SpanScope attribute(String key, String value);

    /** Set a long attribute. Returns {@code this} for chaining. */
    SpanScope attribute(String key, long value);

    /**
     * Mark the span as failed and record the exception (aligns with the exception model). Returns
     * {@code this} for chaining.
     */
    SpanScope error(Throwable error);

    @Override
    void close();
  }
}
