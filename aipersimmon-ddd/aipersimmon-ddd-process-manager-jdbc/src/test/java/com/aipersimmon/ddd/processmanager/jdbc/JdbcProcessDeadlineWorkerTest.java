package com.aipersimmon.ddd.processmanager.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodecRegistry;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinitionRegistry;
import com.aipersimmon.ddd.processmanager.jdbc.deadline.JdbcProcessDeadlineWorker;
import com.aipersimmon.ddd.processmanager.jdbc.lease.AtomicUpdateProcessDialect;
import com.aipersimmon.ddd.processmanager.jdbc.lease.WorkerId;
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
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/** Deadline-worker contract against H2: firing, superseded-generation no-op, retry→DEAD (P2③). */
class JdbcProcessDeadlineWorkerTest {

    private static final ProcessBusinessKey ORDER = new ProcessBusinessKey("order-1");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneOffset.UTC);

    private JdbcTemplate jdbc;
    private JdbcProcessRuntime runtime;
    private JdbcProcessDeadlineStore deadlineStore;
    private JdbcProcessInstanceStore instanceStore;
    private JdbcProcessUnitOfWork unitOfWork;
    private final AtomicUpdateProcessDialect dialect = new AtomicUpdateProcessDialect("h2");
    private final AtomicInteger ids = new AtomicInteger();
    private final AtomicInteger tokens = new AtomicInteger();

    @BeforeEach
    void setUp() {
        DataSource dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("classpath:aipersimmon/db/migration/process-manager/h2/V1__aipersimmon_process_manager.sql")
                .build();
        jdbc = new JdbcTemplate(dataSource);
        instanceStore = new JdbcProcessInstanceStore(jdbc);
        JdbcProcessTransitionStore transitionStore = new JdbcProcessTransitionStore(jdbc);
        JdbcProcessEffectStore effectStore = new JdbcProcessEffectStore(jdbc);
        deadlineStore = new JdbcProcessDeadlineStore(jdbc);
        unitOfWork = new JdbcProcessUnitOfWork(new DataSourceTransactionManager(dataSource));
        runtime = new JdbcProcessRuntime(
                instanceStore, transitionStore, effectStore, deadlineStore,
                new ProcessDefinitionRegistry(List.of(new TestFulfilment.Definition())),
                new ProcessPayloadCodecRegistry(TestFulfilment.payloadCodecs()),
                new ProcessStateCodecRegistry(List.of(TestFulfilment.stateCodec())),
                unitOfWork, CLOCK, () -> "id-" + ids.incrementAndGet(),
                DuplicateBusinessKeyPolicy.REJECT, 3);
    }

    private JdbcProcessDeadlineWorker worker(ProcessRetryPolicy policy) {
        return new JdbcProcessDeadlineWorker(
                jdbc, dialect, deadlineStore, instanceStore,
                new ProcessPayloadCodecRegistry(TestFulfilment.payloadCodecs()),
                runtime, unitOfWork, policy, CLOCK, new WorkerId("worker-test"), 50,
                Duration.ofSeconds(30), () -> "lease-" + tokens.incrementAndGet());
    }

    private ProcessAdvanceResult start() {
        return runtime.start(TestFulfilment.TYPE, ORDER,
                new TestFulfilment.Started("order-1"), CommandContext.root("msg-start", null));
    }

    private String instanceLifecycle() {
        return jdbc.queryForObject("SELECT lifecycle FROM aipersimmon_process_instance", String.class);
    }

    @Test
    void firesADueDeadlineAndAdvancesTheProcess() {
        ProcessAdvanceResult started = start();
        runtime.handle(started.processRef(), new TestFulfilment.ArmDeadline(), CommandContext.root("msg-arm", null));

        int fired = worker(zeroBackoff(3)).pollOnce();

        assertEquals(1, fired);
        assertEquals("FIRED", jdbc.queryForObject(
                "SELECT status FROM aipersimmon_process_deadline", String.class));
        assertEquals("COMPLETED", instanceLifecycle());
        assertEquals("REVIEW_EXPIRED", jdbc.queryForObject(
                "SELECT outcome FROM aipersimmon_process_instance", String.class));
    }

    @Test
    void aSupersededGenerationIsAnAuditableNoOp() {
        ProcessAdvanceResult started = start();
        // Arm the same deadline name twice: generation 1 then 2, both pending.
        runtime.handle(started.processRef(), new TestFulfilment.ArmDeadline(), CommandContext.root("arm-1", null));
        runtime.handle(started.processRef(), new TestFulfilment.ArmDeadline(), CommandContext.root("arm-2", null));
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM aipersimmon_process_deadline WHERE status = 'PENDING'", Long.class));

        worker(zeroBackoff(3)).pollOnce();

        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM aipersimmon_process_deadline WHERE generation = 1 AND status = 'CANCELLED'",
                Long.class), "the superseded generation is a no-op");
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM aipersimmon_process_deadline WHERE generation = 2 AND status = 'FIRED'",
                Long.class), "the current generation fires");
        assertEquals("COMPLETED", instanceLifecycle());
    }

    @Test
    void exhaustingFireRetriesMovesTheDeadlineToDeadAndSuspends() {
        ProcessAdvanceResult started = start();
        runtime.handle(started.processRef(), new TestFulfilment.ArmPoisonDeadline(),
                CommandContext.root("msg-arm", null));
        JdbcProcessDeadlineWorker worker = worker(zeroBackoff(2));

        worker.pollOnce(); // attempt 1 -> handle throws -> retry
        worker.pollOnce(); // attempt 2 -> DEAD + suspend

        assertEquals("DEAD", jdbc.queryForObject(
                "SELECT status FROM aipersimmon_process_deadline", String.class));
        var instance = jdbc.queryForMap(
                "SELECT lifecycle, suspension_source FROM aipersimmon_process_instance");
        assertEquals("SUSPENDED", instance.get("LIFECYCLE"));
        assertEquals("DEADLINE", instance.get("SUSPENSION_SOURCE"));
    }

    @Test
    void aFiredDeadlineKeepsTheCorrelationOfTheFlowThatArmedIt() {
        ProcessAdvanceResult started = start();
        // Arm the deadline under a specific correlation/trace; the fire must stay on that chain.
        runtime.handle(started.processRef(), new TestFulfilment.ArmDeadline(),
                CommandContext.root("msg-arm", "trace-arm"));

        assertEquals(1, worker(zeroBackoff(3)).pollOnce());

        var fired = jdbc.queryForMap(
                "SELECT correlation_id, trace_id FROM aipersimmon_process_transition WHERE decision_code = 'deadline-fired'");
        assertEquals("msg-arm", fired.get("CORRELATION_ID"), "the timeout fires under the arming flow's correlation");
        assertEquals("trace-arm", fired.get("TRACE_ID"));
    }

    @Test
    void aDeadlineCancelledWhileInFlightDoesNotFire() {
        ProcessAdvanceResult started = start();
        runtime.handle(started.processRef(), new TestFulfilment.ArmDeadline(), CommandContext.root("msg-arm", null));

        // A worker claimed the deadline (IN_FLIGHT) and its lease has since expired (crash/slow worker).
        Instant now = CLOCK.instant();
        unitOfWork.execute(() -> dialect.claimDueDeadlines(
                jdbc, now, 10, new WorkerId("owner-A"), "token-A", now.minusSeconds(1)));
        assertEquals("IN_FLIGHT", jdbc.queryForObject(
                "SELECT status FROM aipersimmon_process_deadline", String.class));

        // The business cancels the deadline while it is in flight.
        runtime.handle(started.processRef(), new TestFulfilment.CancelReview(), CommandContext.root("msg-cancel", null));

        int fired = worker(zeroBackoff(3)).pollOnce();

        assertEquals(0, fired, "a cancelled deadline is not fired");
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT status FROM aipersimmon_process_deadline", String.class));
        assertEquals("RUNNING", instanceLifecycle(), "the timeout did not drive the instance");
        assertEquals("NO_WAIT", jdbc.queryForObject(
                "SELECT business_step FROM aipersimmon_process_instance", String.class));
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
