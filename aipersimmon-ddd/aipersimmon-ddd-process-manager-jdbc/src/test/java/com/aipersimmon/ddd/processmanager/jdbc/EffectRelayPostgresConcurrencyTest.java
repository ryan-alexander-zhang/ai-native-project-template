package com.aipersimmon.ddd.processmanager.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodecRegistry;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinitionRegistry;
import com.aipersimmon.ddd.processmanager.engine.lease.WorkerId;
import com.aipersimmon.ddd.processmanager.engine.relay.CommandEffectDispatcher;
import com.aipersimmon.ddd.processmanager.engine.relay.EffectDispatcherRegistry;
import com.aipersimmon.ddd.processmanager.engine.relay.ProcessEffectRelay;
import com.aipersimmon.ddd.processmanager.engine.retry.ProcessRetryPolicy;
import com.aipersimmon.ddd.processmanager.engine.runtime.DefaultProcessRuntime;
import com.aipersimmon.ddd.processmanager.engine.runtime.DuplicateBusinessKeyPolicy;
import com.aipersimmon.ddd.processmanager.engine.runtime.SpringTxProcessUnitOfWork;
import com.aipersimmon.ddd.processmanager.jdbc.lease.JdbcProcessClaimStrategy;
import com.aipersimmon.ddd.processmanager.jdbc.lease.SkipLockedProcessDialect;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessEffectStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessTransitionStore;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.testsupport.SharedContainers;
import com.aipersimmon.ddd.testsupport.TestDataSources;
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
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The SKIP LOCKED claim gate on a real PostgreSQL: two workers polling concurrently over many due
 * effects must claim disjoint sets, so every effect is dispatched exactly once — no double delivery
 * from a lost race.
 */
class EffectRelayPostgresConcurrencyTest {

  private static final PostgreSQLContainer<?> POSTGRES = SharedContainers.postgres();

  private JdbcTemplate jdbc;
  private DefaultProcessRuntime runtime;
  private JdbcProcessEffectStore effectStore;
  private JdbcProcessInstanceStore instanceStore;
  private SpringTxProcessUnitOfWork unitOfWork;
  private final SkipLockedProcessDialect dialect = new SkipLockedProcessDialect("postgresql");
  private final ConcurrentDispatchBus bus = new ConcurrentDispatchBus();
  private final AtomicInteger ids = new AtomicInteger();
  private final AtomicInteger tokens = new AtomicInteger();
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneOffset.UTC);

  @BeforeEach
  void setUp() {
    var ds = TestDataSources.from(POSTGRES);
    jdbc = new JdbcTemplate(ds);
    jdbc.execute(
        "DROP TABLE IF EXISTS aipersimmon_process_effect, aipersimmon_process_transition, "
            + "aipersimmon_process_deadline, aipersimmon_process_instance");
    new ResourceDatabasePopulator(
            new ClassPathResource(
                "aipersimmon/db/migration/process-manager/postgresql/V1__aipersimmon_process_manager.sql"),
            new ClassPathResource(
                "aipersimmon/db/migration/process-manager/postgresql/V2__drop_trace_id.sql"))
        .execute(ds);

    instanceStore = new JdbcProcessInstanceStore(jdbc);
    JdbcProcessTransitionStore transitionStore = new JdbcProcessTransitionStore(jdbc);
    effectStore = new JdbcProcessEffectStore(jdbc);
    JdbcProcessDeadlineStore deadlineStore = new JdbcProcessDeadlineStore(jdbc);
    unitOfWork = new SpringTxProcessUnitOfWork(new DataSourceTransactionManager(ds));
    runtime =
        new DefaultProcessRuntime(
            instanceStore,
            transitionStore,
            effectStore,
            deadlineStore,
            new ProcessDefinitionRegistry(List.of(new TestFulfilment.Definition())),
            new ProcessPayloadCodecRegistry(TestFulfilment.payloadCodecs()),
            new ProcessStateCodecRegistry(List.of(TestFulfilment.stateCodec())),
            unitOfWork,
            CLOCK,
            () -> "id-" + ids.incrementAndGet(),
            DuplicateBusinessKeyPolicy.REJECT,
            3);
  }

  @Test
  void twoWorkersClaimingConcurrentlyDeliverEachEffectExactlyOnce() throws InterruptedException {
    int total = 40;
    for (int i = 0; i < total; i++) {
      runtime.start(
          TestFulfilment.TYPE,
          new ProcessBusinessKey("order-" + i),
          new TestFulfilment.Started("order-" + i),
          CommandContext.root("msg-" + i));
    }
    assertEquals(
        (long) total,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM aipersimmon_process_effect WHERE status = 'PENDING'",
            Long.class));

    AtomicInteger delivered = new AtomicInteger();
    Runnable worker = worker(delivered, total);
    Thread a = new Thread(worker, "relay-A");
    Thread b = new Thread(worker, "relay-B");
    a.start();
    b.start();
    a.join(Duration.ofSeconds(30).toMillis());
    b.join(Duration.ofSeconds(30).toMillis());

    assertEquals(total, bus.deliveredMessageIds.size(), "every effect dispatched exactly once");
    Set<String> distinct = new HashSet<>(bus.deliveredMessageIds);
    assertEquals(
        total, distinct.size(), "no effect dispatched twice (SKIP LOCKED claimed disjoint sets)");
    assertEquals(
        (long) total,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM aipersimmon_process_effect WHERE status = 'DELIVERED'",
            Long.class));
  }

  private Runnable worker(AtomicInteger delivered, int total) {
    return () -> {
      ProcessEffectRelay relay =
          new ProcessEffectRelay(
              new JdbcProcessClaimStrategy(
                  jdbc, dialect, new WorkerId("worker-" + Thread.currentThread().getName())),
              effectStore,
              instanceStore,
              new ProcessPayloadCodecRegistry(TestFulfilment.payloadCodecs()),
              new EffectDispatcherRegistry(List.of(new CommandEffectDispatcher(bus))),
              unitOfWork,
              zeroBackoff(),
              CLOCK,
              5,
              Duration.ofSeconds(30),
              () -> "lease-" + tokens.incrementAndGet());
      int idleRounds = 0;
      while (delivered.get() < total && idleRounds < 200) {
        int n = relay.pollOnce();
        if (n == 0) {
          idleRounds++;
          try {
            Thread.sleep(3);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
          }
        } else {
          delivered.addAndGet(n);
          idleRounds = 0;
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

  /** Thread-safe bus that records the messageId of every dispatched command. */
  static final class ConcurrentDispatchBus implements CommandBus {
    final ConcurrentLinkedQueue<String> deliveredMessageIds = new ConcurrentLinkedQueue<>();

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
      deliveredMessageIds.add(messageContext.messageId());
      return null;
    }
  }
}
