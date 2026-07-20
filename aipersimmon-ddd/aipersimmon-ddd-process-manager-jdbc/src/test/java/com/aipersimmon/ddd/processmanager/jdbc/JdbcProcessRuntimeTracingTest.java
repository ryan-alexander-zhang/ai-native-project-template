package com.aipersimmon.ddd.processmanager.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.observability.ObservabilityAttributes;
import com.aipersimmon.ddd.observability.Tracer;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinitionRegistry;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodecRegistry;
import com.aipersimmon.ddd.processmanager.jdbc.runtime.DuplicateBusinessKeyPolicy;
import com.aipersimmon.ddd.processmanager.jdbc.runtime.JdbcProcessRuntime;
import com.aipersimmon.ddd.processmanager.jdbc.runtime.JdbcProcessUnitOfWork;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessEffectStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessTransitionStore;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import com.aipersimmon.ddd.processmanager.model.ProcessRef;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/**
 * The runtime opens a {@code process.advance} span around each advance, so process-manager
 * decisions are visible in traces even when a relay or deadline worker drives them off the
 * command path. Verified with a recording {@link Tracer} — no OpenTelemetry needed here,
 * since the span logic is expressed against the framework-free SPI.
 */
class JdbcProcessRuntimeTracingTest {

    private static final ProcessBusinessKey ORDER = new ProcessBusinessKey("order-1");

    private final AtomicInteger ids = new AtomicInteger();
    private final RecordingTracer tracer = new RecordingTracer();
    private DataSource dataSource;
    private JdbcProcessRuntime runtime;

    @BeforeEach
    void setUp() {
        dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("classpath:aipersimmon/db/migration/process-manager/h2/V1__aipersimmon_process_manager.sql")
                .build();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Clock clock = Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneOffset.UTC);
        runtime = new JdbcProcessRuntime(
                new JdbcProcessInstanceStore(jdbc), new JdbcProcessTransitionStore(jdbc),
                new JdbcProcessEffectStore(jdbc), new JdbcProcessDeadlineStore(jdbc),
                new ProcessDefinitionRegistry(List.of(new TestFulfilment.Definition())),
                new ProcessPayloadCodecRegistry(TestFulfilment.payloadCodecs()),
                new ProcessStateCodecRegistry(List.of(TestFulfilment.stateCodec())),
                new JdbcProcessUnitOfWork(new DataSourceTransactionManager(dataSource)),
                clock, () -> "id-" + ids.incrementAndGet(), DuplicateBusinessKeyPolicy.REJECT, 3,
                com.aipersimmon.ddd.processmanager.jdbc.observe.ProcessObserver.NOOP,
                Optional.empty(), Long.MAX_VALUE, tracer);
    }

    @Test
    void startOpensProcessAdvanceSpanWithAttributes() {
        runtime.start(TestFulfilment.TYPE, ORDER,
                new TestFulfilment.Started("order-1"), CommandContext.root("m1", "trace-1"));

        assertEquals(1, tracer.spans.size());
        RecordingTracer.RecordedSpan span = tracer.spans.get(0);
        assertEquals("process.advance test.proc", span.name);
        assertTrue(span.closed);
        assertEquals("test.proc", span.attrs.get(ObservabilityAttributes.PROCESS_TYPE));
        assertEquals("order-1", span.attrs.get(ObservabilityAttributes.PROCESS_BUSINESS_KEY));
        assertNotNull(span.attrs.get(ObservabilityAttributes.PROCESS_INSTANCE_ID));
        assertNotNull(span.attrs.get(ObservabilityAttributes.LIFECYCLE));
        assertNull(span.error);
    }

    @Test
    void failedAdvanceMarksSpanError() {
        ProcessRef ghost = new ProcessRef(
                new ProcessInstanceId("ghost"), TestFulfilment.TYPE, new ProcessBusinessKey("missing"));

        assertThrows(RuntimeException.class, () -> runtime.handle(
                ghost, new TestFulfilment.Advance(), CommandContext.root("m2", null)));

        assertEquals(1, tracer.spans.size());
        RecordingTracer.RecordedSpan span = tracer.spans.get(0);
        assertEquals("process.advance test.proc", span.name);
        assertTrue(span.closed);
        assertNotNull(span.error);
    }

    /** A Tracer that records the spans it opens, for assertions. */
    static final class RecordingTracer implements Tracer {
        final List<RecordedSpan> spans = new ArrayList<>();

        @Override
        public SpanScope startSpan(String name) {
            RecordedSpan span = new RecordedSpan(name);
            spans.add(span);
            return span;
        }

        static final class RecordedSpan implements SpanScope {
            final String name;
            final Map<String, String> attrs = new HashMap<>();
            Throwable error;
            boolean closed;

            RecordedSpan(String name) {
                this.name = name;
            }

            @Override
            public SpanScope attribute(String key, String value) {
                if (value != null) {
                    attrs.put(key, value);
                }
                return this;
            }

            @Override
            public SpanScope attribute(String key, long value) {
                attrs.put(key, Long.toString(value));
                return this;
            }

            @Override
            public SpanScope error(Throwable error) {
                this.error = error;
                return this;
            }

            @Override
            public void close() {
                this.closed = true;
            }
        }
    }
}
