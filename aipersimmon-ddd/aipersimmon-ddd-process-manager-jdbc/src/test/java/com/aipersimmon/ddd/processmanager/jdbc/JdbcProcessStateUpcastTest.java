package com.aipersimmon.ddd.processmanager.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.codec.EncodedPayload;
import com.aipersimmon.ddd.processmanager.codec.PayloadType;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodec;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodec;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodecRegistry;
import com.aipersimmon.ddd.processmanager.definition.ProcessContext;
import com.aipersimmon.ddd.processmanager.definition.ProcessDecision;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinition;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinitionRegistry;
import com.aipersimmon.ddd.processmanager.definition.ProcessInput;
import com.aipersimmon.ddd.processmanager.jdbc.runtime.DuplicateBusinessKeyPolicy;
import com.aipersimmon.ddd.processmanager.jdbc.runtime.JdbcProcessRuntime;
import com.aipersimmon.ddd.processmanager.jdbc.runtime.JdbcProcessUnitOfWork;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessEffectStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessTransitionStore;
import com.aipersimmon.ddd.processmanager.model.DecisionCode;
import com.aipersimmon.ddd.processmanager.model.DefinitionVersion;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessRef;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.aipersimmon.ddd.processmanager.model.StateSchemaVersion;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
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
 * State-schema upcast against H2. A live instance stays pinned to the definition version and state
 * schema it was written with: the registry keeps the old definition and codec alongside the new
 * active ones, and on the next advance the old codec upcasts the legacy wire shape into the current
 * model on decode — never a decode-failure-then-reopen and never a silent schema bump under a
 * running instance.
 */
class JdbcProcessStateUpcastTest {

  private static final ProcessType TYPE = new ProcessType("upcast.proc");
  private static final ProcessBusinessKey ORDER = new ProcessBusinessKey("order-1");
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneOffset.UTC);

  private JdbcTemplate jdbc;
  private JdbcProcessRuntime runtime;
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
    runtime =
        new JdbcProcessRuntime(
            new JdbcProcessInstanceStore(jdbc),
            new JdbcProcessTransitionStore(jdbc),
            new JdbcProcessEffectStore(jdbc),
            new JdbcProcessDeadlineStore(jdbc),
            // The old v1 definition is kept (inactive) alongside the new active v2, for pinned
            // instances.
            new ProcessDefinitionRegistry(List.of(new DefinitionV1(), new DefinitionV2())),
            new ProcessPayloadCodecRegistry(List.of(advanceCodec())),
            // Both schema codecs registered: v1 still reads live schema-1 instances, v2 serves new
            // ones.
            new ProcessStateCodecRegistry(List.of(stateCodecV1(), stateCodecV2())),
            new JdbcProcessUnitOfWork(new DataSourceTransactionManager(dataSource)),
            CLOCK,
            () -> "id-" + ids.incrementAndGet(),
            DuplicateBusinessKeyPolicy.REJECT,
            3);
  }

  @Test
  void aPinnedOldSchemaInstanceIsUpcastOnReadAndStaysOnItsSchema() {
    // A live instance persisted under the old v1 definition and schema 1, in the legacy wire shape.
    Timestamp now = Timestamp.from(CLOCK.instant());
    jdbc.update(
        """
                INSERT INTO aipersimmon_process_instance (
                    instance_id, process_type, business_key, definition_version, state_schema_version,
                    lifecycle, business_step, revision, state_payload_type, state_payload, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
        "inst-1",
        TYPE.value(),
        ORDER.value(),
        "v1",
        1,
        "RUNNING",
        "S1",
        1L,
        "upcast.state",
        base64("OLD"),
        now,
        now);
    ProcessRef ref = new ProcessRef(new ProcessInstanceId("inst-1"), TYPE, ORDER);

    runtime.handle(ref, new Advance(), CommandContext.root("msg-adv"));

    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT state_schema_version FROM aipersimmon_process_instance", Integer.class),
        "the instance stays pinned to its schema — no silent bump under a running instance");
    assertEquals(
        "v1",
        jdbc.queryForObject(
            "SELECT definition_version FROM aipersimmon_process_instance", String.class),
        "and pinned to its definition version");
    assertEquals(
        "upcast:OLD-advanced",
        decodedState(),
        "the legacy bytes were upcast into the current model on decode, reacted, and re-encoded under v1");
  }

  private String decodedState() {
    String stored =
        jdbc.queryForObject("SELECT state_payload FROM aipersimmon_process_instance", String.class);
    return new String(java.util.Base64.getDecoder().decode(stored), StandardCharsets.UTF_8);
  }

  private static String base64(String raw) {
    return java.util.Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  /** The current business state. */
  private record V(String data) {}

  private record Advance() implements ProcessInput {}

  /** The old, now-inactive definition, kept for pinned instances: schema 1. */
  private static final class DefinitionV1 implements ProcessDefinition<V> {
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
      return false;
    }

    @Override
    public StateSchemaVersion stateSchemaVersion() {
      return new StateSchemaVersion(1);
    }

    @Override
    public ProcessDecision<V> start(ProcessInput input, ProcessContext context) {
      throw new UnsupportedOperationException("v1 no longer starts new instances");
    }

    @Override
    public ProcessDecision<V> react(V state, ProcessInput input, ProcessContext context) {
      return new ProcessDecision<>(
          new V(state.data() + "-advanced"),
          ProcessLifecycle.RUNNING,
          new ProcessStep("S2"),
          Optional.empty(),
          new DecisionCode("advanced"),
          List.of());
    }
  }

  /** The new active definition for fresh instances: schema 2 (present to prove coexistence). */
  private static final class DefinitionV2 implements ProcessDefinition<V> {
    @Override
    public ProcessType processType() {
      return TYPE;
    }

    @Override
    public DefinitionVersion definitionVersion() {
      return new DefinitionVersion("v2");
    }

    @Override
    public boolean activeForNewInstances() {
      return true;
    }

    @Override
    public StateSchemaVersion stateSchemaVersion() {
      return new StateSchemaVersion(2);
    }

    @Override
    public ProcessDecision<V> start(ProcessInput input, ProcessContext context) {
      return new ProcessDecision<>(
          new V("fresh"),
          ProcessLifecycle.RUNNING,
          new ProcessStep("S1"),
          Optional.empty(),
          new DecisionCode("started"),
          List.of());
    }

    @Override
    public ProcessDecision<V> react(V state, ProcessInput input, ProcessContext context) {
      return new ProcessDecision<>(
          new V(state.data() + "-advanced"),
          ProcessLifecycle.RUNNING,
          new ProcessStep("S2"),
          Optional.empty(),
          new DecisionCode("advanced"),
          List.of());
    }
  }

  /**
   * Old schema (1): decode upcasts the legacy bytes into the current model; encode stays schema-1.
   */
  private static ProcessStateCodec<V> stateCodecV1() {
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
      public EncodedPayload encode(V state) {
        return new EncodedPayload(
            new PayloadType("upcast.state", 1), state.data().getBytes(StandardCharsets.UTF_8));
      }

      @Override
      public V decode(EncodedPayload payload) {
        return new V("upcast:" + new String(payload.data(), StandardCharsets.UTF_8));
      }
    };
  }

  /**
   * Current schema (2): unused by the pinned instance, present to prove the registry holds both.
   */
  private static ProcessStateCodec<V> stateCodecV2() {
    return new ProcessStateCodec<>() {
      @Override
      public ProcessType processType() {
        return TYPE;
      }

      @Override
      public StateSchemaVersion schemaVersion() {
        return new StateSchemaVersion(2);
      }

      @Override
      public EncodedPayload encode(V state) {
        return new EncodedPayload(
            new PayloadType("upcast.state", 2),
            ("v2|" + state.data()).getBytes(StandardCharsets.UTF_8));
      }

      @Override
      public V decode(EncodedPayload payload) {
        String raw = new String(payload.data(), StandardCharsets.UTF_8);
        return new V(raw.startsWith("v2|") ? raw.substring(3) : raw);
      }
    };
  }

  private static ProcessPayloadCodec<Advance> advanceCodec() {
    return new ProcessPayloadCodec<>() {
      @Override
      public PayloadType payloadType() {
        return new PayloadType("upcast.advance", 1);
      }

      @Override
      public Class<Advance> javaType() {
        return Advance.class;
      }

      @Override
      public EncodedPayload encode(Advance value) {
        return new EncodedPayload(payloadType(), new byte[0]);
      }

      @Override
      public Advance decode(EncodedPayload payload) {
        return new Advance();
      }
    };
  }
}
