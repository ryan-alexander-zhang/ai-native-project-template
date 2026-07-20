package com.aipersimmon.ddd.observability;

/**
 * The default {@link Tracer}: opens inert spans that record nothing. Applies whenever
 * the optional {@code aipersimmon-ddd-observability-otel} module is absent, so the
 * domain spine runs exactly as it did before tracing was introduced.
 */
public final class NoOpTracer implements Tracer {

    /** The shared stateless instance. */
    public static final Tracer INSTANCE = new NoOpTracer();

    private static final SpanScope NOOP_SPAN = new SpanScope() {
        @Override
        public SpanScope attribute(String key, String value) {
            return this;
        }

        @Override
        public SpanScope attribute(String key, long value) {
            return this;
        }

        @Override
        public SpanScope error(Throwable error) {
            return this;
        }

        @Override
        public void close() {
        }
    };

    private NoOpTracer() {
    }

    @Override
    public SpanScope startSpan(String name) {
        return NOOP_SPAN;
    }
}
