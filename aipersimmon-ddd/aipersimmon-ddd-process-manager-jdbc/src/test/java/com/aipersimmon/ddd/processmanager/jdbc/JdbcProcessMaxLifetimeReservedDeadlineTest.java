package com.aipersimmon.ddd.processmanager.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.codec.EncodedPayload;
import com.aipersimmon.ddd.processmanager.codec.PayloadType;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodec;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodec;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodecRegistry;
import com.aipersimmon.ddd.processmanager.definition.MaxLifetimeExceeded;
import com.aipersimmon.ddd.processmanager.definition.ProcessContext;
import com.aipersimmon.ddd.processmanager.definition.ProcessDecision;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinition;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinitionRegistry;
import com.aipersimmon.ddd.processmanager.definition.ProcessInput;
import com.aipersimmon.ddd.processmanager.effect.CancelDeadline;
import com.aipersimmon.ddd.processmanager.effect.ProcessEffect;
import com.aipersimmon.ddd.processmanager.effect.ScheduleDeadline;
import com.aipersimmon.ddd.processmanager.engine.observe.ProcessObserver;
import com.aipersimmon.ddd.processmanager.engine.runtime.DefaultProcessRuntime;
import com.aipersimmon.ddd.processmanager.engine.runtime.DuplicateBusinessKeyPolicy;
import com.aipersimmon.ddd.processmanager.engine.runtime.MaxLifetimeExceededCodec;
import com.aipersimmon.ddd.processmanager.engine.runtime.SpringTxProcessUnitOfWork;
import com.aipersimmon.ddd.processmanager.exception.UnsupportedProcessInputException;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessEffectStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessTransitionStore;
import com.aipersimmon.ddd.processmanager.model.DecisionCode;
import com.aipersimmon.ddd.processmanager.model.DefinitionVersion;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.aipersimmon.ddd.processmanager.model.StateSchemaVersion;
import java.nio.charset.StandardCharsets;
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

/**
 * A definition that schedules or cancels the reserved max-lifetime name in its start decision wins
 * over the default backstop (against H2).
 */
class JdbcProcessMaxLifetimeReservedDeadlineTest {

  private static final ProcessType TYPE = new ProcessType("test.maxlife");
  private static final ProcessBusinessKey ORDER = new ProcessBusinessKey("order-1");
  private static final Instant T0 = Instant.parse("2026-07-16T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
  private static final Duration MAX_LIFETIME = Duration.ofDays(30);
  private static final Instant CUSTOM_DUE = T0.plus(Duration.ofDays(5)); // != T0 + 30d default

  private JdbcTemplate jdbc;
  private JdbcProcessInstanceStore instanceStore;
  private JdbcProcessTransitionStore transitionStore;
  private JdbcProcessEffectStore effectStore;
  private JdbcProcessDeadlineStore deadlineStore;
  private SpringTxProcessUnitOfWork unitOfWork;
  private final AtomicInteger ids = new AtomicInteger();

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
    transitionStore = new JdbcProcessTransitionStore(jdbc);
    effectStore = new JdbcProcessEffectStore(jdbc);
    deadlineStore = new JdbcProcessDeadlineStore(jdbc);
    unitOfWork = new SpringTxProcessUnitOfWork(new DataSourceTransactionManager(dataSource));
  }

  private DefaultProcessRuntime runtime() {
    return new DefaultProcessRuntime(
        instanceStore,
        transitionStore,
        effectStore,
        deadlineStore,
        new ProcessDefinitionRegistry(List.of(new Definition())),
        new ProcessPayloadCodecRegistry(codecs()),
        new ProcessStateCodecRegistry(List.of(stateCodec())),
        unitOfWork,
        CLOCK,
        () -> "id-" + ids.incrementAndGet(),
        DuplicateBusinessKeyPolicy.REJECT,
        3,
        ProcessObserver.NOOP,
        Optional.of(MAX_LIFETIME),
        Long.MAX_VALUE);
  }

  private long reservedDeadlineCount() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM aipersimmon_process_deadline WHERE name = 'aipersimmon.max-lifetime'",
        Long.class);
  }

  @Test
  void aDefinitionReschedulingTheReservedNameInStartWinsOverTheDefaultBackstop() {
    runtime().start(TYPE, ORDER, new Reschedule(), CommandContext.root("msg-start"));

    assertEquals(
        1L,
        reservedDeadlineCount(),
        "the default backstop steps aside; only the definition's schedule stands");
    assertEquals(
        CUSTOM_DUE,
        jdbc.queryForObject(
                "SELECT due_at FROM aipersimmon_process_deadline WHERE name = 'aipersimmon.max-lifetime'",
                java.sql.Timestamp.class)
            .toInstant(),
        "the effective deadline is the definition's custom due time, not the default TTL");
  }

  @Test
  void aDefinitionCancellingTheReservedNameInStartWinsOverTheDefaultBackstop() {
    runtime().start(TYPE, ORDER, new Cancel(), CommandContext.root("msg-start"));

    assertEquals(
        0L,
        reservedDeadlineCount(),
        "the definition cancelled the reserved name; the default backstop does not re-arm it");
  }

  // Inputs driving the two start decisions.
  record Reschedule() implements ProcessInput {}

  record Cancel() implements ProcessInput {}

  record State(int n) {}

  private static final class Definition implements ProcessDefinition<State> {
    @Override
    public ProcessType processType() {
      return TYPE;
    }

    @Override
    public DefinitionVersion definitionVersion() {
      return new DefinitionVersion("v1");
    }

    @Override
    public boolean activeForNewInstances() {
      return true;
    }

    @Override
    public StateSchemaVersion stateSchemaVersion() {
      return new StateSchemaVersion(1);
    }

    @Override
    public ProcessDecision<State> start(ProcessInput input, ProcessContext context) {
      List<ProcessEffect> effects =
          switch (input) {
            case Reschedule ignored ->
                List.of(
                    new ScheduleDeadline(
                        MaxLifetimeExceeded.DEADLINE_NAME, CUSTOM_DUE, new MaxLifetimeExceeded()));
            case Cancel ignored -> List.of(new CancelDeadline(MaxLifetimeExceeded.DEADLINE_NAME));
            default ->
                throw new UnsupportedProcessInputException("unexpected start input: " + input);
          };
      return new ProcessDecision<>(
          new State(0),
          ProcessLifecycle.RUNNING,
          new ProcessStep("S1"),
          Optional.empty(),
          new DecisionCode("started"),
          effects);
    }

    @Override
    public ProcessDecision<State> react(State state, ProcessInput input, ProcessContext context) {
      throw new UnsupportedProcessInputException("unexpected input: " + input);
    }
  }

  private static List<ProcessPayloadCodec<?>> codecs() {
    return List.of(
        TestFulfilment.payloadCodec(
            "test.maxlife.reschedule", Reschedule.class, r -> "", s -> new Reschedule()),
        TestFulfilment.payloadCodec(
            "test.maxlife.cancel", Cancel.class, c -> "", s -> new Cancel()),
        new MaxLifetimeExceededCodec());
  }

  private static ProcessStateCodec<State> stateCodec() {
    return new ProcessStateCodec<>() {
      @Override
      public ProcessType processType() {
        return TYPE;
      }

      @Override
      public StateSchemaVersion schemaVersion() {
        return new StateSchemaVersion(1);
      }

      @Override
      public EncodedPayload encode(State state) {
        return new EncodedPayload(
            new PayloadType("test.maxlife.state", 1),
            Integer.toString(state.n()).getBytes(StandardCharsets.UTF_8));
      }

      @Override
      public State decode(EncodedPayload payload) {
        return new State(Integer.parseInt(new String(payload.data(), StandardCharsets.UTF_8)));
      }
    };
  }
}
