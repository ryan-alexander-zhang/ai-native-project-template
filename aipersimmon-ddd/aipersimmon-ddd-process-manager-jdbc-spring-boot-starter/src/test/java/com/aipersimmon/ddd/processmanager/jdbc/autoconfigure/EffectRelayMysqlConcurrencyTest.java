package com.aipersimmon.ddd.processmanager.jdbc.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodecRegistry;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinitionRegistry;
import com.aipersimmon.ddd.processmanager.jdbc.lease.SkipLockedProcessDialect;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The SKIP LOCKED claim gate on a real MySQL 8 (design-00004 §10), symmetric to the
 * PostgreSQL gate: two concurrent workers must claim disjoint effects, so each is
 * dispatched exactly once — and it exercises the shipped {@code mysql-schema.sql}.
 */
@Testcontainers
class EffectRelayMysqlConcurrencyTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    private JdbcTemplate jdbc;
    private JdbcProcessRuntime runtime;
    private JdbcProcessEffectStore effectStore;
    private JdbcProcessInstanceStore instanceStore;
    private JdbcProcessUnitOfWork unitOfWork;
    private final SkipLockedProcessDialect dialect = new SkipLockedProcessDialect("mysql");
    private final ConcurrentBus bus = new ConcurrentBus();
    private final AtomicInteger ids = new AtomicInteger();
    private final AtomicInteger tokens = new AtomicInteger();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() throws Exception {
        SimpleDriverDataSource ds = new SimpleDriverDataSource(
                (java.sql.Driver) Class.forName("com.mysql.cj.jdbc.Driver").getDeclaredConstructor().newInstance(),
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS aipersimmon_process_effect");
        jdbc.execute("DROP TABLE IF EXISTS aipersimmon_process_transition");
        jdbc.execute("DROP TABLE IF EXISTS aipersimmon_process_deadline");
        jdbc.execute("DROP TABLE IF EXISTS aipersimmon_process_instance");
        new ResourceDatabasePopulator(new ClassPathResource(
                "META-INF/aipersimmon-ddd/process-manager/mysql-schema.sql")).execute(ds);

        instanceStore = new JdbcProcessInstanceStore(jdbc);
        JdbcProcessTransitionStore transitionStore = new JdbcProcessTransitionStore(jdbc);
        effectStore = new JdbcProcessEffectStore(jdbc);
        JdbcProcessDeadlineStore deadlineStore = new JdbcProcessDeadlineStore(jdbc);
        unitOfWork = new JdbcProcessUnitOfWork(new DataSourceTransactionManager(ds));
        runtime = new JdbcProcessRuntime(
                instanceStore, transitionStore, effectStore, deadlineStore,
                new ProcessDefinitionRegistry(List.of(new StarterTestProcess.Definition())),
                new ProcessPayloadCodecRegistry(List.of(
                        StarterTestProcess.beginCodec(), StarterTestProcess.doThingCodec())),
                new ProcessStateCodecRegistry(List.of(StarterTestProcess.stateCodec())),
                unitOfWork, CLOCK, () -> "id-" + ids.incrementAndGet(),
                DuplicateBusinessKeyPolicy.REJECT, 3);
    }

    @Test
    void twoWorkersClaimingConcurrentlyDeliverEachEffectExactlyOnce() throws InterruptedException {
        int total = 30;
        for (int i = 0; i < total; i++) {
            runtime.start(StarterTestProcess.TYPE, new ProcessBusinessKey("order-" + i),
                    new StarterTestProcess.Begin("order-" + i), CommandContext.root("msg-" + i, null));
        }

        AtomicInteger delivered = new AtomicInteger();
        Thread a = new Thread(worker(delivered, total), "relay-A");
        Thread b = new Thread(worker(delivered, total), "relay-B");
        a.start();
        b.start();
        a.join(Duration.ofSeconds(30).toMillis());
        b.join(Duration.ofSeconds(30).toMillis());

        assertEquals(total, bus.messageIds.size(), "every effect dispatched exactly once");
        Set<String> distinct = new HashSet<>(bus.messageIds);
        assertEquals(total, distinct.size(), "no effect dispatched twice under SKIP LOCKED");
    }

    private Runnable worker(AtomicInteger delivered, int total) {
        return () -> {
            JdbcProcessEffectRelay relay = new JdbcProcessEffectRelay(
                    jdbc, dialect, effectStore, instanceStore,
                    new ProcessPayloadCodecRegistry(List.of(
                            StarterTestProcess.beginCodec(), StarterTestProcess.doThingCodec())),
                    new EffectDispatcherRegistry(List.of(new CommandEffectDispatcher(bus))),
                    unitOfWork, zeroBackoff(), CLOCK,
                    new WorkerId("worker-" + Thread.currentThread().getName()), 5,
                    Duration.ofSeconds(30), () -> "lease-" + tokens.incrementAndGet());
            int idle = 0;
            while (delivered.get() < total && idle < 300) {
                int n = relay.pollOnce();
                if (n == 0) {
                    idle++;
                    try {
                        Thread.sleep(3);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                } else {
                    delivered.addAndGet(n);
                    idle = 0;
                }
            }
        };
    }

    private static ProcessRetryPolicy zeroBackoff() {
        return new ProcessRetryPolicy() {
            @Override
            public Duration backoff(int attempt) {
                return Duration.ZERO;
            }

            @Override
            public int maxAttempts() {
                return 5;
            }
        };
    }

    static final class ConcurrentBus implements CommandBus {
        final ConcurrentLinkedQueue<String> messageIds = new ConcurrentLinkedQueue<>();

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
            messageIds.add(messageContext.messageId());
            return null;
        }
    }
}
