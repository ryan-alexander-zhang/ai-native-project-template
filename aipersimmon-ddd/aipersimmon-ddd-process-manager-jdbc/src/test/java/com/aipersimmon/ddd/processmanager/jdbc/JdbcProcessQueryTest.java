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
import com.aipersimmon.ddd.processmanager.jdbc.runtime.JdbcProcessQuery;
import com.aipersimmon.ddd.processmanager.jdbc.runtime.JdbcProcessRuntime;
import com.aipersimmon.ddd.processmanager.jdbc.runtime.JdbcProcessUnitOfWork;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessDeadlineStore.DeadlineStatus;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessEffectStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessEffectStore.EffectStatus;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessTransitionStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.ProcessInstanceCriteria;
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

/** Read-only operational queries over the four-table store against H2 (plan-00003 P3② item 3). */
class JdbcProcessQueryTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneOffset.UTC);

    private JdbcTemplate jdbc;
    private JdbcProcessRuntime runtime;
    private JdbcProcessQuery query;
    private JdbcProcessEffectStore effectStore;
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
                .addScript("classpath:process-schema-h2.sql")
                .build();
        jdbc = new JdbcTemplate(dataSource);
        instanceStore = new JdbcProcessInstanceStore(jdbc);
        JdbcProcessTransitionStore transitionStore = new JdbcProcessTransitionStore(jdbc);
        effectStore = new JdbcProcessEffectStore(jdbc);
        JdbcProcessDeadlineStore deadlineStore = new JdbcProcessDeadlineStore(jdbc);
        unitOfWork = new JdbcProcessUnitOfWork(new DataSourceTransactionManager(dataSource));
        runtime = new JdbcProcessRuntime(
                instanceStore, transitionStore, effectStore, deadlineStore,
                new ProcessDefinitionRegistry(List.of(new TestFulfilment.Definition())),
                new ProcessPayloadCodecRegistry(TestFulfilment.payloadCodecs()),
                new ProcessStateCodecRegistry(List.of(TestFulfilment.stateCodec())),
                unitOfWork, CLOCK, () -> "id-" + ids.incrementAndGet(),
                DuplicateBusinessKeyPolicy.REJECT, 3);
        query = new JdbcProcessQuery(instanceStore, transitionStore, effectStore, deadlineStore, CLOCK);
    }

    private ProcessAdvanceResult start(String order) {
        return runtime.start(TestFulfilment.TYPE, new ProcessBusinessKey(order),
                new TestFulfilment.Started(order), CommandContext.root("start-" + order, null));
    }

    @Test
    void searchesInstancesByCriteriaWithPaging() {
        start("order-1");
        start("order-2");

        assertEquals(2, query.search(ProcessInstanceCriteria.any().withProcessType(TestFulfilment.TYPE), 10, 0).size());
        assertEquals(1, query.search(
                ProcessInstanceCriteria.any().withBusinessKey(new ProcessBusinessKey("order-1")), 10, 0).size());
        assertEquals(1, query.search(ProcessInstanceCriteria.any(), 1, 0).size(), "limit is honoured");
        assertEquals(1, query.search(ProcessInstanceCriteria.any(), 1, 1).size(), "offset pages the next row");
        assertEquals(0, query.search(ProcessInstanceCriteria.any().withBusinessKey(new ProcessBusinessKey("nope")), 10, 0).size());
    }

    @Test
    void returnsTheTransitionTimelineInOrder() {
        ProcessAdvanceResult started = start("order-1");
        runtime.handle(started.processRef(), new TestFulfilment.Advance(), CommandContext.root("adv", null));

        var timeline = query.timeline(started.processRef());
        assertEquals(List.of("START", "ADVANCE"), timeline.stream().map(t -> t.transitionKind()).toList());
        assertEquals("S2", timeline.get(1).toStep());
    }

    @Test
    void listsPendingAndDeadEffectWorklists() {
        start("order-1");
        assertEquals(1, query.effects(EffectStatus.PENDING, 10).size());
        assertEquals(0, query.effects(EffectStatus.DEAD, 10).size());

        relay(new FailingBus(), 1).pollOnce(); // -> DEAD
        List<com.aipersimmon.ddd.processmanager.jdbc.store.ProcessEffectView> dead =
                query.effects(EffectStatus.DEAD, 10);
        assertEquals(1, dead.size());
        assertTrue(dead.get(0).lastError().isPresent(), "a dead effect carries its last error");
    }

    @Test
    void listsPendingDeadlineWorklist() {
        ProcessAdvanceResult started = start("order-1");
        runtime.handle(started.processRef(), new TestFulfilment.ArmDeadline(), CommandContext.root("arm", null));

        var pending = query.deadlines(DeadlineStatus.PENDING, 10);
        assertEquals(1, pending.size());
        assertEquals("REVIEW", pending.get(0).name());
        assertEquals(1L, pending.get(0).generation());
    }

    @Test
    void scansStuckInstancesPastTheThreshold() {
        start("order-1");
        assertEquals(1, relay(new RecordingBus(), 3).pollOnce(), "deliver the effect so no pending work remains");

        JdbcProcessQuery later = new JdbcProcessQuery(
                instanceStore, new JdbcProcessTransitionStore(jdbc), effectStore,
                new JdbcProcessDeadlineStore(jdbc), Clock.fixed(CLOCK.instant().plus(Duration.ofHours(1)), ZoneOffset.UTC));
        assertEquals(0, later.stuckInstances(Duration.ofHours(2), 10).size());
        assertEquals(1, later.stuckInstances(Duration.ofMinutes(30), 10).size());
    }

    private JdbcProcessEffectRelay relay(CommandBus bus, int maxAttempts) {
        return new JdbcProcessEffectRelay(
                jdbc, dialect, effectStore, instanceStore,
                new ProcessPayloadCodecRegistry(TestFulfilment.payloadCodecs()),
                new EffectDispatcherRegistry(List.of(new CommandEffectDispatcher(bus))),
                unitOfWork, zeroBackoff(maxAttempts), CLOCK, new WorkerId("w"), 10,
                Duration.ofSeconds(30), () -> "lease-" + tokens.incrementAndGet());
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

    static final class RecordingBus implements CommandBus {
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
            return null;
        }
    }

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
