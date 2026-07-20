package com.aipersimmon.ddd.processmanager.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodecRegistry;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinitionRegistry;
import com.aipersimmon.ddd.processmanager.exception.ProcessPayloadTooLargeException;
import com.aipersimmon.ddd.processmanager.jdbc.deadline.JdbcProcessDeadlineWorker;
import com.aipersimmon.ddd.processmanager.jdbc.lease.AtomicUpdateProcessDialect;
import com.aipersimmon.ddd.processmanager.jdbc.lease.WorkerId;
import com.aipersimmon.ddd.processmanager.jdbc.observe.ProcessObserver;
import com.aipersimmon.ddd.processmanager.jdbc.retry.ProcessRetryPolicy;
import com.aipersimmon.ddd.processmanager.jdbc.runtime.DuplicateBusinessKeyPolicy;
import com.aipersimmon.ddd.processmanager.jdbc.runtime.JdbcProcessRuntime;
import com.aipersimmon.ddd.processmanager.jdbc.runtime.JdbcProcessUnitOfWork;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessEffectStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessTransitionStore;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.runtime.ProcessAdvanceResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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

/** Max-lifetime backstop deadline and payload.max-bytes enforcement against H2. */
class JdbcProcessMaxLifetimeTest {

    private static final ProcessBusinessKey ORDER = new ProcessBusinessKey("order-1");
    private static final Instant T0 = Instant.parse("2026-07-16T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

    private JdbcTemplate jdbc;
    private DataSource dataSource;
    private JdbcProcessInstanceStore instanceStore;
    private JdbcProcessTransitionStore transitionStore;
    private JdbcProcessEffectStore effectStore;
    private JdbcProcessDeadlineStore deadlineStore;
    private JdbcProcessUnitOfWork unitOfWork;
    private final AtomicUpdateProcessDialect dialect = new AtomicUpdateProcessDialect("h2");
    private final AtomicInteger ids = new AtomicInteger();
    private final AtomicInteger tokens = new AtomicInteger();

    @BeforeEach
    void setUp() {
        dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("classpath:aipersimmon/db/migration/process-manager/h2/V1__aipersimmon_process_manager.sql")
                .addScript("classpath:aipersimmon/db/migration/process-manager/h2/V2__drop_trace_id.sql")
                .build();
        jdbc = new JdbcTemplate(dataSource);
        instanceStore = new JdbcProcessInstanceStore(jdbc);
        transitionStore = new JdbcProcessTransitionStore(jdbc);
        effectStore = new JdbcProcessEffectStore(jdbc);
        deadlineStore = new JdbcProcessDeadlineStore(jdbc);
        unitOfWork = new JdbcProcessUnitOfWork(new DataSourceTransactionManager(dataSource));
    }

    private JdbcProcessRuntime runtime(Optional<Duration> maxLifetime, long maxPayloadBytes) {
        return new JdbcProcessRuntime(
                instanceStore, transitionStore, effectStore, deadlineStore,
                new ProcessDefinitionRegistry(List.of(new TestFulfilment.Definition())),
                new ProcessPayloadCodecRegistry(TestFulfilment.payloadCodecs()),
                new ProcessStateCodecRegistry(List.of(TestFulfilment.stateCodec())),
                unitOfWork, CLOCK, () -> "id-" + ids.incrementAndGet(),
                DuplicateBusinessKeyPolicy.REJECT, 3, ProcessObserver.NOOP, maxLifetime, maxPayloadBytes);
    }

    private ProcessAdvanceResult start(JdbcProcessRuntime runtime) {
        return runtime.start(TestFulfilment.TYPE, ORDER,
                new TestFulfilment.Started("order-1"), CommandContext.root("msg-start"));
    }

    @Test
    void armsAMaxLifetimeBackstopOnStartWhenConfigured() {
        start(runtime(Optional.of(Duration.ofDays(30)), Long.MAX_VALUE));

        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM aipersimmon_process_deadline WHERE name = 'aipersimmon.max-lifetime' "
                        + "AND status = 'PENDING'", Long.class));
        assertEquals(Instant.parse("2026-08-15T00:00:00Z"),
                jdbc.queryForObject("SELECT due_at FROM aipersimmon_process_deadline", java.sql.Timestamp.class)
                        .toInstant());
    }

    @Test
    void doesNotArmABackstopWhenNotConfigured() {
        start(runtime(Optional.empty(), Long.MAX_VALUE));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM aipersimmon_process_deadline", Long.class));
    }

    @Test
    void firingTheBackstopLetsTheDefinitionDecide() {
        JdbcProcessRuntime runtime = runtime(Optional.of(Duration.ofDays(30)), Long.MAX_VALUE);
        start(runtime);

        // A worker whose clock is past the 30-day TTL claims and fires the backstop.
        Clock later = Clock.fixed(T0.plus(Duration.ofDays(31)), ZoneOffset.UTC);
        JdbcProcessDeadlineWorker worker = new JdbcProcessDeadlineWorker(
                jdbc, dialect, deadlineStore, instanceStore,
                new ProcessPayloadCodecRegistry(TestFulfilment.payloadCodecs()),
                runtime, unitOfWork, zeroBackoff(3), later, new WorkerId("dw"), 50,
                Duration.ofSeconds(30), () -> "lease-" + tokens.incrementAndGet());

        assertEquals(1, worker.pollOnce());
        assertEquals("COMPLETED", jdbc.queryForObject(
                "SELECT lifecycle FROM aipersimmon_process_instance", String.class));
        assertEquals("MAX_LIFETIME", jdbc.queryForObject(
                "SELECT outcome FROM aipersimmon_process_instance", String.class));
    }

    @Test
    void rejectsAPayloadThatExceedsTheConfiguredCap() {
        JdbcProcessRuntime runtime = runtime(Optional.empty(), 1L);
        assertThrows(ProcessPayloadTooLargeException.class, () -> start(runtime));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM aipersimmon_process_instance", Long.class),
                "nothing is persisted when encoding is rejected");
    }

    private static ProcessRetryPolicy zeroBackoff(int maxAttempts) {
        return new ProcessRetryPolicy() {
            @Override
            public Duration backoff(int attempt) {
                return Duration.ZERO;
            }

            @Override
            public int maxAttempts() {
                return maxAttempts;
            }
        };
    }
}
