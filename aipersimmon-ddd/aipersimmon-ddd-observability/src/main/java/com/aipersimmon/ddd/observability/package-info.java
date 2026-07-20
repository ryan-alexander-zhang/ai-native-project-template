/**
 * Framework-free observability contracts for the DDD building blocks.
 *
 * <p>Two seams are declared here as plain SPIs, both with a no-op default so the
 * rest of the library can depend on them without an OpenTelemetry (or any tracing)
 * dependency:
 *
 * <ul>
 *   <li>{@link com.aipersimmon.ddd.observability.StoreAndForwardTracer} — captures
 *       the current trace context when a durable row (outbox message, process
 *       effect/deadline/transition) is written, and restores it — as a linked span —
 *       when a poller later claims and dispatches that row. This is the one hop that
 *       in-process ambient context and library auto-instrumentation cannot bridge,
 *       because it crosses a self-owned table and a thread/time boundary.</li>
 *   <li>{@link com.aipersimmon.ddd.observability.Tracer} — opens spans around the
 *       self-written domain spine (command/query dispatch, domain-event handling,
 *       inbound ACL, process-manager advance) that auto-instrumentation does not
 *       recognise. Used by modules that must stay framework-free; Spring-side spans
 *       are created directly against OpenTelemetry in the optional otel module.</li>
 * </ul>
 *
 * <p>{@link com.aipersimmon.ddd.observability.ObservabilityAttributes} is the stable
 * span-attribute catalog so traces are queryable by business dimension.
 *
 * <p>The concrete OpenTelemetry implementations live in the optional
 * {@code aipersimmon-ddd-observability-otel} module. When that module is absent,
 * {@link com.aipersimmon.ddd.observability.NoOpTracer} and
 * {@link com.aipersimmon.ddd.observability.NoOpStoreAndForwardTracer} apply and the
 * runtime behaves exactly as it did before observability was introduced.
 */
package com.aipersimmon.ddd.observability;
