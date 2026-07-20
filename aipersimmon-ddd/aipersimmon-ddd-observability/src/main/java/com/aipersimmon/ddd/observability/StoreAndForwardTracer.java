package com.aipersimmon.ddd.observability;

/**
 * Carries trace context across a durable store-and-forward hop.
 *
 * <p>Ambient (thread-local) trace context and library auto-instrumentation cannot
 * bridge the seam where a row is written in one transaction and dispatched later by
 * a polling worker in a different thread and transaction: the original context is
 * gone and no instrumentation understands a self-owned table. The two sides of this
 * SPI close that seam, and only that seam — synchronous propagation still rides the
 * ambient context untouched.
 *
 * <ul>
 *   <li>{@link #captureCurrent()} runs on the writing thread (where the creating
 *       span is active) and serialises the current context into an opaque
 *       {@code traceparent}/{@code trace_state} pair for storage on the row.</li>
 *   <li>{@link #restore(String, String, String)} runs on the dispatching worker: it
 *       reconstructs the stored context and opens a span <em>linked</em> back to the
 *       creating span (not a child — the delay and fan-out make a link the correct
 *       relationship), kept active until the returned {@link Scope} is closed.</li>
 * </ul>
 *
 * <p>Implementations treat the stored strings as opaque; only the OTEL implementation
 * interprets them. The default {@link NoOpStoreAndForwardTracer} captures nothing and
 * restores an inert scope.
 */
public interface StoreAndForwardTracer {

    /**
     * Serialise the trace context active on the current (writing) thread. Called
     * inside the write transaction so the captured context is the creating span.
     *
     * @return the opaque carrier to persist on the durable row; never {@code null}
     *     (use {@link Captured#NONE} when there is no active context)
     */
    Captured captureCurrent();

    /**
     * Reconstruct a stored context and open a span linked back to the creating span.
     *
     * @param traceparent the stored W3C traceparent, may be {@code null}
     * @param traceState the stored W3C tracestate, may be {@code null}
     * @param workItemId identifier of the item being dispatched, for the span name
     *     and attributes (e.g. an effect id or outbox message id)
     * @return a scope keeping the restored span active; close it when dispatch ends.
     *     Never {@code null}.
     */
    Scope restore(String traceparent, String traceState, String workItemId);

    /** The opaque, storable serialisation of a trace context. */
    record Captured(String traceparent, String traceState) {

        /** Absence of any trace context — both fields {@code null}. */
        public static final Captured NONE = new Captured(null, null);
    }

    /** An active span scope; closing it ends the span. */
    interface Scope extends AutoCloseable {

        @Override
        void close();
    }
}
