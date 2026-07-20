package com.aipersimmon.ddd.processmanager.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.observability.NoOpTracer;
import com.aipersimmon.ddd.observability.StoreAndForwardTracer;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodecRegistry;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinitionRegistry;
import com.aipersimmon.ddd.processmanager.effect.ProcessEffectKind;
import com.aipersimmon.ddd.processmanager.jdbc.lease.AtomicUpdateProcessDialect;
import com.aipersimmon.ddd.processmanager.jdbc.lease.WorkerId;
import com.aipersimmon.ddd.processmanager.jdbc.observe.ProcessObserver;
import com.aipersimmon.ddd.processmanager.jdbc.relay.DecodedProcessEffect;
import com.aipersimmon.ddd.processmanager.jdbc.relay.EffectDispatcherRegistry;
import com.aipersimmon.ddd.processmanager.jdbc.relay.JdbcProcessEffectRelay;
import com.aipersimmon.ddd.processmanager.jdbc.relay.ProcessEffectDispatcher;
import com.aipersimmon.ddd.processmanager.jdbc.retry.ProcessRetryPolicy;
import com.aipersimmon.ddd.processmanager.jdbc.runtime.DuplicateBusinessKeyPolicy;
import com.aipersimmon.ddd.processmanager.jdbc.runtime.JdbcProcessRuntime;
import com.aipersimmon.ddd.processmanager.jdbc.runtime.JdbcProcessUnitOfWork;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessEffectStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessTransitionStore;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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
 * The PM effect hop is store-and-forward: the runtime captures the advance's trace context onto
 * the effect row, and the relay restores it (as a linked span) when it dispatches. Verified with
 * a recording {@link StoreAndForwardTracer} — the OTEL link behaviour itself is covered elsewhere.
 */
class JdbcProcessEffectTracingTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneOffset.UTC);
    private static final ProcessBusinessKey ORDER = new ProcessBusinessKey("order-1");

    private final AtomicInteger ids = new AtomicInteger();
    private final RecordingStoreTracer tracer = new RecordingStoreTracer();
    private final RecordingDispatcher dispatcher = new RecordingDispatcher();

    private JdbcTemplate jdbc;
    private JdbcProcessEffectStore effectStore;
    private JdbcProcessInstanceStore instanceStore;
    private JdbcProcessUnitOfWork unitOfWork;
    private JdbcProcessRuntime runtime;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("classpath:aipersimmon/db/migration/process-manager/h2/V1__aipersimmon_process_manager.sql")
                .addScript("classpath:aipersimmon/db/migration/process-manager/h2/V2__drop_trace_id.sql")
                .build();
        jdbc = new JdbcTemplate(dataSource);
        instanceStore = new JdbcProcessInstanceStore(jdbc);
        effectStore = new JdbcProcessEffectStore(jdbc);
        unitOfWork = new JdbcProcessUnitOfWork(new DataSourceTransactionManager(dataSource));
        runtime = new JdbcProcessRuntime(
                instanceStore, new JdbcProcessTransitionStore(jdbc), effectStore,
                new JdbcProcessDeadlineStore(jdbc),
                new ProcessDefinitionRegistry(List.of(new TestFulfilment.Definition())),
                new ProcessPayloadCodecRegistry(TestFulfilment.payloadCodecs()),
                new ProcessStateCodecRegistry(List.of(TestFulfilment.stateCodec())),
                unitOfWork, CLOCK, () -> "id-" + ids.incrementAndGet(),
                DuplicateBusinessKeyPolicy.REJECT, 3, ProcessObserver.NOOP,
                Optional.empty(), Long.MAX_VALUE, NoOpTracer.INSTANCE, tracer);
    }

    private JdbcProcessEffectRelay relay() {
        return new JdbcProcessEffectRelay(
                jdbc, new AtomicUpdateProcessDialect("h2"), effectStore, instanceStore,
                new ProcessPayloadCodecRegistry(TestFulfilment.payloadCodecs()),
                new EffectDispatcherRegistry(List.of(dispatcher)),
                unitOfWork, zeroBackoff(), CLOCK, new WorkerId("worker-test"), 50,
                Duration.ofSeconds(30), () -> "lease-" + ids.incrementAndGet(),
                ProcessObserver.NOOP, tracer);
    }

    @Test
    void runtimeCapturesTraceContextOntoTheEffectRow() {
        runtime.start(TestFulfilment.TYPE, ORDER,
                new TestFulfilment.Started("order-1"), CommandContext.root("msg-start"));

        String traceparent = jdbc.queryForObject(
                "SELECT traceparent FROM aipersimmon_process_effect", String.class);
        assertEquals("tp-eff", traceparent);
    }

    @Test
    void relayRestoresTheCapturedContextAroundDispatch() {
        runtime.start(TestFulfilment.TYPE, ORDER,
                new TestFulfilment.Started("order-1"), CommandContext.root("msg-start"));

        int delivered = relay().pollOnce();

        assertEquals(1, delivered);
        assertEquals(1, dispatcher.dispatched.size());
        assertEquals(List.of("tp-eff"), tracer.restoredTraceparents);
        assertEquals(1, tracer.restoredSpanNames.size());
        assertTrue(tracer.restoredSpanNames.get(0).startsWith("effect.dispatch "),
                "restored span name was " + tracer.restoredSpanNames.get(0));
    }

    private static ProcessRetryPolicy zeroBackoff() {
        return new ProcessRetryPolicy() {
            @Override
            public Duration backoff(int attempt) {
                return Duration.ZERO;
            }

            @Override
            public int maxAttempts() {
                return 3;
            }
        };
    }

    /** Captures a fixed context and records restore calls. */
    static final class RecordingStoreTracer implements StoreAndForwardTracer {
        final List<String> restoredTraceparents = new ArrayList<>();
        final List<String> restoredSpanNames = new ArrayList<>();

        @Override
        public Captured captureCurrent() {
            return new Captured("tp-eff", "ts-eff");
        }

        @Override
        public Scope restore(String traceparent, String traceState, String spanName) {
            restoredTraceparents.add(traceparent);
            restoredSpanNames.add(spanName);
            return () -> { };
        }
    }

    /** Records dispatched command effects and succeeds. */
    static final class RecordingDispatcher implements ProcessEffectDispatcher {
        final List<DecodedProcessEffect> dispatched = new ArrayList<>();

        @Override
        public ProcessEffectKind kind() {
            return ProcessEffectKind.DISPATCH_COMMAND;
        }

        @Override
        public void dispatch(DecodedProcessEffect effect, CommandContext context) {
            dispatched.add(effect);
        }
    }
}
