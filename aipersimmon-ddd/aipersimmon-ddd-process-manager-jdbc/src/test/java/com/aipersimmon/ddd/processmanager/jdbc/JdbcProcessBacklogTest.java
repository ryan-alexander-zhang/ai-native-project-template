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
import com.aipersimmon.ddd.processmanager.jdbc.observe.JdbcProcessBacklog;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/** Backlog SLI aggregate reads over the four-table store against H2. */
class JdbcProcessBacklogTest {

  private static final ProcessBusinessKey ORDER = new ProcessBusinessKey("order-1");
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneOffset.UTC);

  private JdbcTemplate jdbc;
  private JdbcProcessRuntime runtime;
  private JdbcProcessEffectStore effectStore;
  private JdbcProcessInstanceStore instanceStore;
  private JdbcProcessDeadlineStore deadlineStore;
  private JdbcProcessUnitOfWork unitOfWork;
  private final AtomicUpdateProcessDialect dialect = new AtomicUpdateProcessDialect("h2");
  private final AtomicInteger ids = new AtomicInteger();
  private final AtomicInteger tokens = new AtomicInteger();

  @BeforeEach
  void setUp() {
    DataSource dataSource =
        new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .addScript(
                "classpath:aipersimmon/db/migration/process-manager/h2/V1__aipersimmon_process_manager.sql")
            .addScript(
                "classpath:aipersimmon/db/migration/process-manager/h2/V2__drop_trace_id.sql")
            .build();
    jdbc = new JdbcTemplate(dataSource);
    instanceStore = new JdbcProcessInstanceStore(jdbc);
    JdbcProcessTransitionStore transitionStore = new JdbcProcessTransitionStore(jdbc);
    effectStore = new JdbcProcessEffectStore(jdbc);
    deadlineStore = new JdbcProcessDeadlineStore(jdbc);
    unitOfWork = new JdbcProcessUnitOfWork(new DataSourceTransactionManager(dataSource));
    ProcessPayloadCodecRegistry payloadCodecs =
        new ProcessPayloadCodecRegistry(TestFulfilment.payloadCodecs());
    runtime =
        new JdbcProcessRuntime(
            instanceStore,
            transitionStore,
            effectStore,
            deadlineStore,
            new ProcessDefinitionRegistry(List.of(new TestFulfilment.Definition())),
            payloadCodecs,
            new ProcessStateCodecRegistry(List.of(TestFulfilment.stateCodec())),
            unitOfWork,
            CLOCK,
            () -> "id-" + ids.incrementAndGet(),
            DuplicateBusinessKeyPolicy.REJECT,
            3);
  }

  private ProcessAdvanceResult start() {
    return runtime.start(
        TestFulfilment.TYPE,
        ORDER,
        new TestFulfilment.Started("order-1"),
        CommandContext.root("msg-start"));
  }

  private JdbcProcessBacklog backlogAt(Instant at) {
    return new JdbcProcessBacklog(
        effectStore, deadlineStore, instanceStore, Clock.fixed(at, ZoneOffset.UTC));
  }

  private JdbcProcessEffectRelay relay(CommandBus bus, int maxAttempts) {
    return new JdbcProcessEffectRelay(
        jdbc,
        dialect,
        effectStore,
        instanceStore,
        new ProcessPayloadCodecRegistry(TestFulfilment.payloadCodecs()),
        new EffectDispatcherRegistry(List.of(new CommandEffectDispatcher(bus))),
        unitOfWork,
        zeroBackoff(maxAttempts),
        CLOCK,
        new WorkerId("w"),
        10,
        Duration.ofSeconds(30),
        () -> "lease-" + tokens.incrementAndGet());
  }

  @Test
  void countsDeadEffectsAndSuspendedInstancesBySource() {
    start();
    relay(new FailingBus(), 1).pollOnce(); // exhausts retries -> DEAD + SUSPENDED (source EFFECT)

    JdbcProcessBacklog backlog = backlogAt(CLOCK.instant());
    assertEquals(1L, backlog.deadEffects());
    assertEquals(0L, backlog.deadDeadlines());
    assertEquals(Map.of("EFFECT", 1L), backlog.suspendedInstancesBySource());
    assertEquals(1L, backlog.suspendedInstances());
  }

  @Test
  void oldestPendingAgesReflectDwellPastTheScheduledTime() {
    ProcessAdvanceResult started = start(); // stages one due command effect
    runtime.handle(
        started.processRef(),
        new TestFulfilment.ArmDeadline(),
        CommandContext.root("msg-arm")); // schedules a due REVIEW deadline

    JdbcProcessBacklog backlog = backlogAt(CLOCK.instant().plusSeconds(5));
    assertEquals(Duration.ofSeconds(5), backlog.oldestPendingEffectAge());
    assertEquals(Duration.ofSeconds(5), backlog.oldestPendingDeadlineAge());
  }

  @Test
  void reportsNoDwellWhenThereIsNoDueWork() {
    JdbcProcessBacklog backlog = backlogAt(CLOCK.instant());
    assertEquals(Duration.ZERO, backlog.oldestPendingEffectAge());
    assertEquals(Duration.ZERO, backlog.oldestPendingDeadlineAge());
    assertEquals(0L, backlog.suspendedInstances());
  }

  @Test
  void flagsAnActiveInstanceWithNoPendingWorkAsStuckPastTheThreshold() {
    start();
    assertEquals(1, relay(new RecordingBus(), 3).pollOnce(), "the staged effect is delivered");

    JdbcProcessBacklog backlog = backlogAt(CLOCK.instant().plus(Duration.ofHours(1)));
    assertEquals(
        0L, backlog.stuckInstances(Duration.ofHours(2)), "not idle long enough for a 2h threshold");
    assertEquals(
        1L,
        backlog.stuckInstances(Duration.ofMinutes(30)),
        "idle past a 30m threshold with no pending work");
  }

  @Test
  void doesNotFlagAnInstanceThatStillHasPendingWork() {
    start(); // leaves a pending (undelivered) effect
    JdbcProcessBacklog backlog = backlogAt(CLOCK.instant().plus(Duration.ofHours(1)));
    assertTrue(
        backlog.stuckInstances(Duration.ofMinutes(1)) == 0L,
        "a pending effect means it is not stuck");
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
