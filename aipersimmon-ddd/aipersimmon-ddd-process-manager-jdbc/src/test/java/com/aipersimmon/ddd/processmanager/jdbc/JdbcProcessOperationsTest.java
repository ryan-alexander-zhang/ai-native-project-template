package com.aipersimmon.ddd.processmanager.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodecRegistry;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinitionRegistry;
import com.aipersimmon.ddd.processmanager.jdbc.deadline.JdbcProcessDeadlineWorker;
import com.aipersimmon.ddd.processmanager.jdbc.lease.AtomicUpdateProcessDialect;
import com.aipersimmon.ddd.processmanager.jdbc.lease.WorkerId;
import com.aipersimmon.ddd.processmanager.jdbc.operation.JdbcProcessOperations;
import com.aipersimmon.ddd.processmanager.jdbc.relay.CommandEffectDispatcher;
import com.aipersimmon.ddd.processmanager.jdbc.relay.EffectDispatcherRegistry;
import com.aipersimmon.ddd.processmanager.jdbc.relay.JdbcProcessEffectRelay;
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

/** Operator recovery + suspended-input parking against H2. */
class JdbcProcessOperationsTest {

    private static final ProcessBusinessKey ORDER = new ProcessBusinessKey("order-1");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneOffset.UTC);

    private JdbcTemplate jdbc;
    private JdbcProcessRuntime runtime;
    private JdbcProcessEffectStore effectStore;
    private JdbcProcessInstanceStore instanceStore;
    private JdbcProcessTransitionStore transitionStore;
    private JdbcProcessDeadlineStore deadlineStore;
    private JdbcProcessUnitOfWork unitOfWork;
    private JdbcProcessOperations operations;
    private final AtomicUpdateProcessDialect dialect = new AtomicUpdateProcessDialect("h2");
    private final FailingBus bus = new FailingBus();
    private final AtomicInteger ids = new AtomicInteger();
    private final AtomicInteger tokens = new AtomicInteger();

    @BeforeEach
    void setUp() {
        DataSource dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("classpath:process-schema-h2.sql")
                .build();
        jdbc = new JdbcTemplate(dataSource);
        instanceStore = new JdbcProcessInstanceStore(jdbc);
        transitionStore = new JdbcProcessTransitionStore(jdbc);
        effectStore = new JdbcProcessEffectStore(jdbc);
        deadlineStore = new JdbcProcessDeadlineStore(jdbc);
        unitOfWork = new JdbcProcessUnitOfWork(new DataSourceTransactionManager(dataSource));
        ProcessPayloadCodecRegistry payloadCodecs = new ProcessPayloadCodecRegistry(TestFulfilment.payloadCodecs());
        runtime = new JdbcProcessRuntime(
                instanceStore, transitionStore, effectStore, deadlineStore,
                new ProcessDefinitionRegistry(List.of(new TestFulfilment.Definition())),
                payloadCodecs, new ProcessStateCodecRegistry(List.of(TestFulfilment.stateCodec())),
                unitOfWork, CLOCK, () -> "id-" + ids.incrementAndGet(),
                DuplicateBusinessKeyPolicy.REJECT, 3);
        operations = new JdbcProcessOperations(
                instanceStore, transitionStore, effectStore, deadlineStore, runtime,
                payloadCodecs, unitOfWork, CLOCK, () -> "op-" + ids.incrementAndGet());
    }

    private ProcessAdvanceResult start() {
        return runtime.start(TestFulfilment.TYPE, ORDER,
                new TestFulfilment.Started("order-1"), CommandContext.root("msg-start", null));
    }

    private String lifecycle() {
        return jdbc.queryForObject("SELECT lifecycle FROM aipersimmon_process_instance", String.class);
    }

    private String suspendViaDeadDeadline() {
        ProcessAdvanceResult started = start();
        runtime.handle(started.processRef(), new TestFulfilment.ArmPoisonDeadline(),
                CommandContext.root("msg-arm", null));
        JdbcProcessDeadlineWorker worker = new JdbcProcessDeadlineWorker(
                jdbc, dialect, deadlineStore, instanceStore,
                new ProcessPayloadCodecRegistry(TestFulfilment.payloadCodecs()),
                runtime, unitOfWork, zeroBackoff(1), CLOCK, new WorkerId("dw"), 10,
                Duration.ofSeconds(30), () -> "dlease-" + tokens.incrementAndGet());
        worker.pollOnce(); // one failed fire with maxAttempts=1 -> DEAD + SUSPENDED (source DEADLINE)
        return jdbc.queryForObject("SELECT deadline_id FROM aipersimmon_process_deadline", String.class);
    }

    private void suspendViaDeadEffect() {
        JdbcProcessEffectRelay relay = new JdbcProcessEffectRelay(
                jdbc, dialect, effectStore, instanceStore,
                new ProcessPayloadCodecRegistry(TestFulfilment.payloadCodecs()),
                new EffectDispatcherRegistry(List.of(new CommandEffectDispatcher(bus))),
                unitOfWork, zeroBackoff(1), CLOCK, new WorkerId("w"), 10,
                Duration.ofSeconds(30), () -> "lease-" + tokens.incrementAndGet());
        relay.pollOnce(); // one failed attempt with maxAttempts=1 -> DEAD + SUSPENDED
    }

    @Test
    void aSuspendedInstanceParksInputInsteadOfReboundingToTheMessageLayer() {
        ProcessAdvanceResult started = start();
        suspendViaDeadEffect();
        assertEquals("SUSPENDED", lifecycle());

        // handle returns normally (parked), so the transport can ack instead of retrying forever.
        ProcessAdvanceResult parked = runtime.handle(
                started.processRef(), new TestFulfilment.Advance(), CommandContext.root("msg-adv", null));
        assertFalse(parked.duplicate());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM aipersimmon_process_transition WHERE transition_kind = 'PARKED'", Long.class));

        // A redelivery of the same input while suspended is a duplicate no-op — it is not parked twice.
        ProcessAdvanceResult again = runtime.handle(
                started.processRef(), new TestFulfilment.Advance(), CommandContext.root("msg-adv", null));
        assertTrue(again.duplicate());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM aipersimmon_process_transition WHERE transition_kind = 'PARKED'", Long.class));
    }

    @Test
    void redrivingTheLastDeadEffectResumesAndReplaysParkedInputs() {
        ProcessAdvanceResult started = start();
        String deadEffectId = started.transitionId() + "#0";
        suspendViaDeadEffect();
        runtime.handle(started.processRef(), new TestFulfilment.Advance(), CommandContext.root("msg-adv", null));

        operations.redriveEffect(deadEffectId, "operator-1", "transient outage cleared");

        assertEquals("RUNNING", lifecycle(), "resumed to its recorded resume lifecycle");
        assertEquals("S2", jdbc.queryForObject(
                "SELECT business_step FROM aipersimmon_process_instance", String.class),
                "the parked Advance was replayed");
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM aipersimmon_process_effect WHERE effect_id = ?", String.class, deadEffectId),
                "the redriven effect is back to PENDING for the relay");
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM aipersimmon_process_transition WHERE transition_kind = 'OPERATOR_REDRIVE_EFFECT'",
                Long.class));
    }

    @Test
    void redrivingADeadDeadlineResumesTheInstance() {
        String deadlineId = suspendViaDeadDeadline();
        assertEquals("SUSPENDED", lifecycle());

        operations.redriveDeadline(deadlineId, 1L, "operator-1", "poison fixed");

        assertEquals("RUNNING", lifecycle(), "resumed once no dead work remains");
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM aipersimmon_process_deadline WHERE deadline_id = ?", String.class, deadlineId),
                "the redriven deadline is back to PENDING for the worker");
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM aipersimmon_process_transition WHERE transition_kind = 'OPERATOR_REDRIVE_DEADLINE'",
                Long.class));
    }

    @Test
    void redriveDeadlineRejectsAStaleGeneration() {
        String deadlineId = suspendViaDeadDeadline();

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> operations.redriveDeadline(deadlineId, 99L, "operator-1", "wrong generation"));

        assertEquals("DEAD", jdbc.queryForObject(
                "SELECT status FROM aipersimmon_process_deadline WHERE deadline_id = ?", String.class, deadlineId),
                "a mismatched generation leaves the deadline untouched");
    }

    @Test
    void multipleParkedInputsAreReplayedInArrivalOrderOnResume() {
        ProcessAdvanceResult started = start();
        String deadEffectId = started.transitionId() + "#0";
        suspendViaDeadEffect();

        // Two distinct inputs arrive while suspended; both are parked, not rebounded.
        runtime.handle(started.processRef(), new TestFulfilment.Advance(), CommandContext.root("msg-adv", null));
        runtime.handle(started.processRef(), new TestFulfilment.FanOut(), CommandContext.root("msg-fan", null));
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM aipersimmon_process_transition WHERE transition_kind = 'PARKED'", Long.class));

        operations.redriveEffect(deadEffectId, "operator-1", "outage cleared");

        assertEquals("RUNNING", lifecycle());
        // Arrival order: Advance (S1->S2) then FanOut (S2->FAN); the reverse would leave step at S2.
        assertEquals("FAN", jdbc.queryForObject(
                "SELECT business_step FROM aipersimmon_process_instance", String.class),
                "parked inputs replayed in arrival order");
    }

    @Test
    void cancelProcessTerminatesAndCancelsPendingWork() {
        ProcessAdvanceResult started = start();

        operations.cancelProcess(started.processRef(), started.revision().value(), "operator-1", "customer request");

        assertEquals("CANCELLED", lifecycle());
        assertEquals("PROCESS_CANCELLED", jdbc.queryForObject(
                "SELECT outcome FROM aipersimmon_process_instance", String.class));
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT status FROM aipersimmon_process_effect", String.class),
                "the not-yet-dispatched effect is cancelled");
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM aipersimmon_process_transition WHERE transition_kind = 'OPERATOR_CANCEL'",
                Long.class));
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

    /** A CommandBus whose sendAs always fails, to drive an effect to DEAD. */
    static final class FailingBus implements CommandBus {
        @Override
        public <R> R send(Command<R> command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <R> R send(Command<R> command, CommandContext cause) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <R> R sendAs(Command<R> command, CommandContext messageContext) {
            throw new IllegalStateException("downstream unavailable");
        }
    }
}
