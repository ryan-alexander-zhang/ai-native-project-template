package com.aipersimmon.ddd.processmanager.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodecRegistry;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinitionRegistry;
import com.aipersimmon.ddd.processmanager.jdbc.lease.AtomicUpdateProcessDialect;
import com.aipersimmon.ddd.processmanager.jdbc.lease.WorkerId;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/** Effect-relay contract against H2: delivery, per-instance ordering, retry/DEAD, fencing. */
class JdbcProcessEffectRelayTest {

    private static final ProcessBusinessKey ORDER = new ProcessBusinessKey("order-1");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneOffset.UTC);

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private JdbcProcessRuntime runtime;
    private JdbcProcessEffectStore effectStore;
    private JdbcProcessInstanceStore instanceStore;
    private JdbcProcessUnitOfWork unitOfWork;
    private AtomicUpdateProcessDialect dialect;
    private RecordingCommandBus bus;
    private final AtomicInteger ids = new AtomicInteger();
    private final AtomicInteger tokens = new AtomicInteger();

    @BeforeEach
    void setUp() {
        dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("classpath:aipersimmon/db/migration/process-manager/h2/V1__aipersimmon_process_manager.sql")
                .build();
        jdbc = new JdbcTemplate(dataSource);
        instanceStore = new JdbcProcessInstanceStore(jdbc);
        JdbcProcessTransitionStore transitionStore = new JdbcProcessTransitionStore(jdbc);
        effectStore = new JdbcProcessEffectStore(jdbc);
        JdbcProcessDeadlineStore deadlineStore = new JdbcProcessDeadlineStore(jdbc);
        unitOfWork = new JdbcProcessUnitOfWork(new DataSourceTransactionManager(dataSource));
        dialect = new AtomicUpdateProcessDialect("h2");
        bus = new RecordingCommandBus();
        runtime = new JdbcProcessRuntime(
                instanceStore, transitionStore, effectStore, deadlineStore,
                new ProcessDefinitionRegistry(List.of(new TestFulfilment.Definition())),
                new ProcessPayloadCodecRegistry(TestFulfilment.payloadCodecs()),
                new ProcessStateCodecRegistry(List.of(TestFulfilment.stateCodec())),
                unitOfWork, CLOCK, () -> "id-" + ids.incrementAndGet(),
                DuplicateBusinessKeyPolicy.REJECT, 3);
    }

    private JdbcProcessEffectRelay relay(ProcessRetryPolicy policy) {
        return new JdbcProcessEffectRelay(
                jdbc, dialect, effectStore, instanceStore,
                new ProcessPayloadCodecRegistry(TestFulfilment.payloadCodecs()),
                new EffectDispatcherRegistry(List.of(new CommandEffectDispatcher(bus))),
                unitOfWork, policy, CLOCK, new WorkerId("worker-test"), 50,
                Duration.ofSeconds(30), () -> "lease-" + tokens.incrementAndGet());
    }

    private ProcessAdvanceResult start() {
        return runtime.start(TestFulfilment.TYPE, ORDER,
                new TestFulfilment.Started("order-1"), CommandContext.root("msg-start", "trace-1"));
    }

    private String status(String effectId) {
        return jdbc.queryForObject(
                "SELECT status FROM aipersimmon_process_effect WHERE effect_id = ?", String.class, effectId);
    }

    @Test
    void deliversAndMarksDeliveredUnderTheEffectIdentity() {
        ProcessAdvanceResult started = start();
        int delivered = relay(zeroBackoff(3)).pollOnce();

        assertEquals(1, delivered);
        assertEquals(1, bus.commands.size());
        String effectId = started.transitionId() + "#0";
        assertEquals(effectId, bus.contexts.get(0).messageId(), "dispatched under the effect id, verbatim");
        assertEquals("msg-start", bus.contexts.get(0).correlationId());
        assertEquals("DELIVERED", status(effectId));
    }

    @Test
    void deliversEffectsOfOneInstanceSeriallyInOrder() {
        ProcessAdvanceResult started = start();
        JdbcProcessEffectRelay relay = relay(zeroBackoff(3));

        // Deliver the start's single effect first, so the fan-out effects become the head.
        relay.pollOnce();
        runtime.handle(started.processRef(), new TestFulfilment.FanOut(), CommandContext.root("msg-fan", null));

        int firstRound = relay.pollOnce();
        assertEquals(1, firstRound, "only the head of the two fan-out effects is delivered");
        assertEquals(List.of("order-1", "first"), references());

        int secondRound = relay.pollOnce();
        assertEquals(1, secondRound, "the second effect is delivered only after the first");
        assertEquals(List.of("order-1", "first", "second"), references());
    }

    @Test
    void twoUndeliveredEffectsOfOneInstanceAreDeliveredOneAtATime() {
        // Two transitions, each staging one effect, both left PENDING. Under the fixed test clock both
        // rows share created_at and both are effect_index=0 — so ordering must rest on a monotonic seq,
        // not on (created_at, effect_index), or the per-instance serial guarantee is broken.
        ProcessAdvanceResult started = start();
        runtime.handle(started.processRef(), new TestFulfilment.Advance(), CommandContext.root("msg-advance", null));
        JdbcProcessEffectRelay relay = relay(zeroBackoff(3));

        assertEquals(1, relay.pollOnce(), "only the head effect is delivered; the later one waits");
        assertEquals(List.of("order-1"), references());

        assertEquals(1, relay.pollOnce(), "the second effect is delivered only after the first");
        assertEquals(List.of("order-1", "again"), references());
    }

    @Test
    void aTransientFailureIsRetriedThenSucceeds() {
        ProcessAdvanceResult started = start();
        bus.failTimes = 1;
        JdbcProcessEffectRelay relay = relay(zeroBackoff(3));

        assertEquals(0, relay.pollOnce(), "first attempt fails");
        String effectId = started.transitionId() + "#0";
        assertEquals("PENDING", status(effectId));

        assertEquals(1, relay.pollOnce(), "retry succeeds");
        assertEquals("DELIVERED", status(effectId));
    }

    @Test
    void exhaustingRetriesMovesTheEffectToDeadAndSuspendsTheInstance() {
        ProcessAdvanceResult started = start();
        bus.failTimes = Integer.MAX_VALUE;
        JdbcProcessEffectRelay relay = relay(zeroBackoff(2));

        relay.pollOnce(); // attempt 1 -> retry
        relay.pollOnce(); // attempt 2 -> DEAD + suspend

        String effectId = started.transitionId() + "#0";
        assertEquals("DEAD", status(effectId));

        var instance = jdbc.queryForMap(
                "SELECT lifecycle, suspension_source, suspending_work_id FROM aipersimmon_process_instance");
        assertEquals("SUSPENDED", instance.get("LIFECYCLE"));
        assertEquals("EFFECT", instance.get("SUSPENSION_SOURCE"));
        assertEquals(effectId, instance.get("SUSPENDING_WORK_ID"));
    }

    @Test
    void aDeadEffectDoesNotReSuspendAnAlreadySuspendedInstance() {
        // The instance is already SUSPENDED by a prior deadline exhaustion (resume target RUNNING).
        ProcessAdvanceResult started = start();
        String instanceId = started.processRef().instanceId().value();
        instanceStore.suspend(
                started.processRef().instanceId(), com.aipersimmon.ddd.processmanager.model.ProcessLifecycle.RUNNING,
                "deadline exhausted", "DEADLINE", "deadline-1", CLOCK.instant());

        // A failing effect on the same instance is claimed (effects are not lifecycle-filtered) and goes DEAD.
        bus.failTimes = Integer.MAX_VALUE;
        JdbcProcessEffectRelay relay = relay(zeroBackoff(2));
        relay.pollOnce(); // attempt 1 -> retry
        relay.pollOnce(); // attempt 2 -> DEAD

        String effectId = started.transitionId() + "#0";
        assertEquals("DEAD", status(effectId));
        // The prior resume target must survive; a re-suspend would clobber it to SUSPENDED and wedge the instance.
        assertEquals("RUNNING", jdbc.queryForObject(
                "SELECT resume_lifecycle FROM aipersimmon_process_instance WHERE instance_id = ?",
                String.class, instanceId), "an already-suspended instance is not re-suspended");
        assertEquals("DEADLINE", jdbc.queryForObject(
                "SELECT suspension_source FROM aipersimmon_process_instance WHERE instance_id = ?",
                String.class, instanceId), "the original suspension source is preserved");
    }

    @Test
    void anExpiredLeaseReclaimDoesNotConsumeTheRetryBudget() {
        ProcessAdvanceResult started = start();
        String effectId = started.transitionId() + "#0";
        Instant now = CLOCK.instant();

        // Three successive expired-lease reclaims (slow workers), with no delivery failure in between.
        for (int i = 0; i < 3; i++) {
            String token = "tok-" + i;
            unitOfWork.execute(() -> dialect.claimDueEffects(
                    jdbc, now, 10, new WorkerId("owner"), token, now.minusSeconds(1)));
        }

        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT attempts FROM aipersimmon_process_effect WHERE effect_id = ?", Integer.class, effectId),
                "a lease-expiry reclaim is not a failed attempt and must not consume the retry budget");
    }

    @Test
    void completionIsFencedByTheLeaseToken() {
        ProcessAdvanceResult started = start();
        String effectId = started.transitionId() + "#0";
        Instant now = CLOCK.instant();
        unitOfWork.execute(() -> dialect.claimDueEffects(
                jdbc, now, 10, new WorkerId("owner-A"), "token-A", now.plusSeconds(30)));

        assertEquals(0, effectStore.markDelivered(effectId, "stale-token", now),
                "a stale token cannot complete the effect");
        assertEquals(1, effectStore.markDelivered(effectId, "token-A", now),
                "the current owner completes it");
    }

    @Test
    void anExpiredLeaseAllowsReclaimForRedelivery() {
        ProcessAdvanceResult started = start();
        String effectId = started.transitionId() + "#0";
        Instant now = CLOCK.instant();

        unitOfWork.execute(() -> dialect.claimDueEffects(
                jdbc, now, 10, new WorkerId("owner-A"), "token-A", now.plusSeconds(30)));
        // Simulate a crash before the delivered mark: force the lease to have expired.
        jdbc.update("UPDATE aipersimmon_process_effect SET lease_until = ? WHERE effect_id = ?",
                java.sql.Timestamp.from(now.minusSeconds(1)), effectId);

        List<String> reclaimed = unitOfWork.execute(() -> dialect.claimDueEffects(
                jdbc, now, 10, new WorkerId("owner-B"), "token-B", now.plusSeconds(30)));

        assertTrue(reclaimed.contains(effectId), "an expired in-flight effect is re-claimed for redelivery");
        assertEquals("IN_FLIGHT", status(effectId));
    }

    @Test
    void redeliversUnderTheSameIdWhenTheAckIsLostAfterASuccessfulDispatch() {
        ProcessAdvanceResult started = start();
        String effectId = started.transitionId() + "#0";
        JdbcProcessEffectRelay relay = relay(zeroBackoff(3));

        assertEquals(1, relay.pollOnce(), "first delivery succeeds");
        assertEquals(1, bus.commands.size());

        // Crash window: the external dispatch happened but the DELIVERED mark was lost, leaving the
        // row IN_FLIGHT with an expired lease. The relay must re-claim and re-dispatch — at-least-once.
        jdbc.update("""
                UPDATE aipersimmon_process_effect
                SET status = 'IN_FLIGHT', delivered_at = NULL, lease_owner = 'crashed',
                    lease_token = 'crashed', lease_until = ? WHERE effect_id = ?""",
                java.sql.Timestamp.from(CLOCK.instant().minusSeconds(1)), effectId);

        assertEquals(1, relay.pollOnce(), "the lost ack is recovered by redelivery");
        assertEquals(2, bus.commands.size(), "the same effect is dispatched twice (at-least-once)");
        assertEquals(effectId, bus.contexts.get(1).messageId(), "redelivery keeps the identical message id");
        assertEquals("DELIVERED", status(effectId));
    }

    @Test
    void aCleanlyDeliveredEffectIsNotRedelivered() {
        start();
        JdbcProcessEffectRelay relay = relay(zeroBackoff(3));

        assertEquals(1, relay.pollOnce());
        assertEquals(0, relay.pollOnce(), "a DELIVERED effect is never claimed again");
        assertEquals(1, bus.commands.size());
    }

    private List<String> references() {
        List<String> refs = new ArrayList<>();
        for (Object command : bus.commands) {
            refs.add(((TestFulfilment.DoWork) command).reference());
        }
        return refs;
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

    /** A CommandBus that records sendAs dispatches and can fail the first N of them. */
    static final class RecordingCommandBus implements CommandBus {
        final List<Object> commands = new ArrayList<>();
        final List<CommandContext> contexts = new ArrayList<>();
        int failTimes = 0;
        private int calls = 0;

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
            calls++;
            if (calls <= failTimes) {
                throw new IllegalStateException("downstream boom");
            }
            commands.add(command);
            contexts.add(messageContext);
            return null;
        }
    }
}
