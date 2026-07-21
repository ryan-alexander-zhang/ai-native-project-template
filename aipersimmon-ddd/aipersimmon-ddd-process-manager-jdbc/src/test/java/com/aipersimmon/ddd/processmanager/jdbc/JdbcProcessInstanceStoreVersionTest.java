package com.aipersimmon.ddd.processmanager.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessInstanceStore.VersionRef;
import com.aipersimmon.ddd.processmanager.jdbc.store.ProcessInstanceRow;
import com.aipersimmon.ddd.processmanager.model.DefinitionVersion;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessOutcome;
import com.aipersimmon.ddd.processmanager.model.ProcessRef;
import com.aipersimmon.ddd.processmanager.model.ProcessRevision;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.aipersimmon.ddd.processmanager.model.StateSchemaVersion;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/** distinctVersionsInUse only pins versions of live (non-terminal) instances, against H2. */
class JdbcProcessInstanceStoreVersionTest {

  private static final ProcessType TYPE = new ProcessType("test.proc");
  private static final StateSchemaVersion SCHEMA = new StateSchemaVersion(1);
  private static final Instant NOW = Instant.parse("2026-07-16T00:00:00Z");

  private JdbcProcessInstanceStore instances;

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
    instances = new JdbcProcessInstanceStore(new JdbcTemplate(dataSource));
  }

  private void insert(
      String instanceId, String order, String definitionVersion, ProcessLifecycle lifecycle) {
    ProcessRef ref =
        new ProcessRef(new ProcessInstanceId(instanceId), TYPE, new ProcessBusinessKey(order));
    instances.insert(
        new ProcessInstanceRow(
            ref,
            new DefinitionVersion(definitionVersion),
            SCHEMA,
            lifecycle,
            new ProcessStep("S1"),
            lifecycle.isTerminal() ? Optional.of(new ProcessOutcome("OK")) : Optional.empty(),
            new ProcessRevision(1L),
            "test.proc.state",
            "S1|0".getBytes(StandardCharsets.UTF_8),
            Optional.empty(),
            Optional.empty()),
        NOW);
  }

  @Test
  void aCompletedInstancesVersionIsNotReturned() {
    // A COMPLETED history row on an old definition version; no live instance uses that version.
    insert("done-1", "order-1", "v0", ProcessLifecycle.COMPLETED);

    List<VersionRef> inUse = instances.distinctVersionsInUse();
    assertFalse(
        inUse.contains(new VersionRef(TYPE, new DefinitionVersion("v0"), SCHEMA)),
        "a terminal instance must not pin its old definition version");
    assertTrue(inUse.isEmpty(), "no live instance depends on any version");
  }

  @Test
  void aLiveInstancesVersionIsStillReturned() {
    insert("done-1", "order-1", "v0", ProcessLifecycle.COMPLETED);
    insert("run-1", "order-2", "v1", ProcessLifecycle.RUNNING);
    insert("susp-1", "order-3", "v2", ProcessLifecycle.SUSPENDED);

    List<VersionRef> inUse = instances.distinctVersionsInUse();
    assertEquals(2, inUse.size(), "only the RUNNING and SUSPENDED instances pin a version");
    assertTrue(inUse.contains(new VersionRef(TYPE, new DefinitionVersion("v1"), SCHEMA)));
    assertTrue(
        inUse.contains(new VersionRef(TYPE, new DefinitionVersion("v2"), SCHEMA)),
        "a suspended instance still needs its definition/codec to resume");
    assertFalse(inUse.contains(new VersionRef(TYPE, new DefinitionVersion("v0"), SCHEMA)));
  }
}
