package com.aipersimmon.ddd.observability;

/**
 * The default {@link StoreAndForwardTracer}: captures no context and restores an
 * inert scope. Applies whenever the optional {@code aipersimmon-ddd-observability-otel}
 * module is absent, so durable relays behave exactly as they did before tracing was
 * introduced.
 */
public final class NoOpStoreAndForwardTracer implements StoreAndForwardTracer {

    /** The shared stateless instance. */
    public static final StoreAndForwardTracer INSTANCE = new NoOpStoreAndForwardTracer();

    private static final Scope NOOP_SCOPE = () -> { };

    private NoOpStoreAndForwardTracer() {
    }

    @Override
    public Captured captureCurrent() {
        return Captured.NONE;
    }

    @Override
    public Scope restore(String traceparent, String traceState, String workItemId) {
        return NOOP_SCOPE;
    }
}
