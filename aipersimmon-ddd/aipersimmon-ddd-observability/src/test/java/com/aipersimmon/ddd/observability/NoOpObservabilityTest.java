package com.aipersimmon.ddd.observability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.aipersimmon.ddd.observability.StoreAndForwardTracer.Captured;
import com.aipersimmon.ddd.observability.StoreAndForwardTracer.Scope;
import com.aipersimmon.ddd.observability.Tracer.SpanScope;
import org.junit.jupiter.api.Test;

/**
 * Pins the contract of the no-op defaults: they must be safe to call everywhere,
 * capture nothing, and never fail — this is the guarantee that an unwired build
 * behaves exactly as it did before observability existed.
 */
class NoOpObservabilityTest {

    @Test
    void captureCurrentYieldsNoContext() {
        Captured captured = NoOpStoreAndForwardTracer.INSTANCE.captureCurrent();

        assertNotNull(captured);
        assertNull(captured.traceparent());
        assertNull(captured.traceState());
        assertSame(Captured.NONE, Captured.NONE);
    }

    @Test
    void restoreYieldsClosableInertScope() {
        Scope scope = NoOpStoreAndForwardTracer.INSTANCE.restore("00-abc-def-01", null, "effect-1");

        assertNotNull(scope);
        assertDoesNotThrow(scope::close);
    }

    @Test
    void spanScopeIsChainableAndInert() {
        try (SpanScope span = NoOpTracer.INSTANCE.startSpan("process.advance Demo")) {
            SpanScope chained = span
                    .attribute(ObservabilityAttributes.PROCESS_TYPE, "Demo")
                    .attribute(ObservabilityAttributes.RETRY_ATTEMPT, 3L)
                    .attribute(ObservabilityAttributes.CORRELATION_ID, null)
                    .error(new IllegalStateException("ignored"));

            assertSame(span, chained);
        }
    }
}
