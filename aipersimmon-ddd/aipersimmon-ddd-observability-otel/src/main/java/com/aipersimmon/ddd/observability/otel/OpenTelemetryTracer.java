package com.aipersimmon.ddd.observability.otel;

import com.aipersimmon.ddd.observability.Tracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;

/**
 * OpenTelemetry implementation of the domain-span {@link Tracer}. Each {@link
 * #startSpan(String)} opens an OTEL span as a child of the active context and makes it
 * current for the duration, so nested spine calls nest correctly along the call stack.
 */
public final class OpenTelemetryTracer implements Tracer {

    private final io.opentelemetry.api.trace.Tracer delegate;

    public OpenTelemetryTracer(io.opentelemetry.api.trace.Tracer delegate) {
        this.delegate = delegate;
    }

    @Override
    public SpanScope startSpan(String name) {
        Span span = delegate.spanBuilder(name).startSpan();
        Scope scope = span.makeCurrent();
        return new OpenTelemetrySpanScope(span, scope);
    }

    /** Wraps an active OTEL span and its current-context scope. */
    static final class OpenTelemetrySpanScope implements SpanScope {

        private final Span span;
        private final Scope scope;

        OpenTelemetrySpanScope(Span span, Scope scope) {
            this.span = span;
            this.scope = scope;
        }

        @Override
        public SpanScope attribute(String key, String value) {
            if (value != null) {
                span.setAttribute(key, value);
            }
            return this;
        }

        @Override
        public SpanScope attribute(String key, long value) {
            span.setAttribute(AttributeKey.longKey(key), value);
            return this;
        }

        @Override
        public SpanScope error(Throwable error) {
            span.setStatus(StatusCode.ERROR, error == null ? "" : String.valueOf(error.getMessage()));
            if (error != null) {
                span.recordException(error);
            }
            return this;
        }

        @Override
        public void close() {
            // Close the current-context scope before ending the span, mirroring open order.
            scope.close();
            span.end();
        }
    }
}
