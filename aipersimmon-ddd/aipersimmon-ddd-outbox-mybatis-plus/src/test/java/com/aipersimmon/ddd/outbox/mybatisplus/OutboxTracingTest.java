package com.aipersimmon.ddd.outbox.mybatisplus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.observability.StoreAndForwardTracer;
import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The MyBatis-Plus outbox is the same store-and-forward hop as the JDBC one: the writer
 * captures the trace context onto the row and the relay restores it (as a linked span) on
 * dispatch. Verified with a recording {@link StoreAndForwardTracer} — no OTEL needed here.
 */
@SpringBootTest(
        classes = OutboxTracingTest.TestApp.class,
        properties = {
                "aipersimmon.ddd.outbox.poll-delay-ms=3600000",
                // A lock name unique to this test so its relay() is never skipped by a ShedLock
                // lock held from another test class in the same JVM run.
                "aipersimmon.ddd.outbox.relay.lock-name=outbox-mybatis-tracing-test"})
class OutboxTracingTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
        @Bean
        CapturingDispatcher capturingDispatcher() {
            return new CapturingDispatcher();
        }

        @Bean
        RecordingTracer recordingTracer() {
            return new RecordingTracer();
        }
    }

    static class CapturingDispatcher implements OutboxDispatcher {
        final List<OutboxMessage> messages = new CopyOnWriteArrayList<>();

        @Override
        public void dispatch(OutboxMessage message) {
            messages.add(message);
        }
    }

    static class RecordingTracer implements StoreAndForwardTracer {
        final List<String> restoredTraceparents = new CopyOnWriteArrayList<>();
        final List<String> restoredSpanNames = new CopyOnWriteArrayList<>();

        @Override
        public Captured captureCurrent() {
            return new Captured("tp-captured", "ts-captured");
        }

        @Override
        public Scope restore(String traceparent, String traceState, String spanName) {
            restoredTraceparents.add(traceparent);
            restoredSpanNames.add(spanName);
            return () -> { };
        }
    }

    @EventType(name = "com.example.ordering.TracedMp", version = 1)
    record SampleEvent(String orderId) implements IntegrationEvent {
    }

    @Autowired
    IntegrationEvents integrationEvents;
    @Autowired
    OutboxRelay relay;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    CapturingDispatcher dispatcher;
    @Autowired
    RecordingTracer tracer;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM aipersimmon_outbox");
        dispatcher.messages.clear();
        tracer.restoredTraceparents.clear();
        tracer.restoredSpanNames.clear();
    }

    @Test
    void writerCapturesTraceContextOntoTheRow() {
        integrationEvents.publish(new SampleEvent("o1"), CommandContext.root("m1", "trace-1"));

        String traceparent = jdbc.queryForObject(
                "SELECT traceparent FROM aipersimmon_outbox", String.class);
        String traceState = jdbc.queryForObject(
                "SELECT trace_state FROM aipersimmon_outbox", String.class);
        assertEquals("tp-captured", traceparent);
        assertEquals("ts-captured", traceState);
    }

    @Test
    void relayRestoresTheCapturedContextAroundDispatch() {
        integrationEvents.publish(new SampleEvent("o1"), CommandContext.root("m1", "trace-1"));

        relay.relay();

        assertEquals(1, dispatcher.messages.size());
        assertEquals(List.of("tp-captured"), tracer.restoredTraceparents);
        assertEquals(1, tracer.restoredSpanNames.size());
        assertTrue(tracer.restoredSpanNames.get(0).startsWith("outbox.publish "),
                "restored span name was " + tracer.restoredSpanNames.get(0));
    }
}
