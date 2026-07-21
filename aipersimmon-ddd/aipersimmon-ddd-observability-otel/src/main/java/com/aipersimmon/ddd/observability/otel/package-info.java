/**
 * OpenTelemetry implementations of the framework-free observability SPIs.
 *
 * <p>{@link com.aipersimmon.ddd.observability.otel.OpenTelemetryTracer} backs the domain-span
 * {@link com.aipersimmon.ddd.observability.Tracer}; {@link
 * com.aipersimmon.ddd.observability.otel.OpenTelemetryStoreAndForwardTracer} backs the {@link
 * com.aipersimmon.ddd.observability.StoreAndForwardTracer}, using the W3C trace context propagator
 * to serialise the active context on write and, on read, opening a span that <em>links</em> back to
 * the captured context (a link, not a parent, because the store-and-forward delay and fan-out make
 * a link the correct relationship).
 *
 * <p>These are plain objects constructed from an {@code io.opentelemetry.api.OpenTelemetry}
 * instance; the Spring auto-configuration that wires them as beans (and pulls in the OTEL Spring
 * Boot starter for boundary auto-instrumentation) lives alongside in this module.
 */
package com.aipersimmon.ddd.observability.otel;
