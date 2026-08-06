package com.aipersimmon.ddd.processmanager.mybatisplus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodecRegistry;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinitionRegistry;
import com.aipersimmon.ddd.processmanager.engine.runtime.DefaultProcessQuery;
import com.aipersimmon.ddd.processmanager.engine.runtime.DefaultProcessRuntime;
import com.aipersimmon.ddd.processmanager.engine.runtime.DuplicateBusinessKeyPolicy;
import com.aipersimmon.ddd.processmanager.engine.runtime.SpringTxProcessUnitOfWork;
import com.aipersimmon.ddd.processmanager.exception.ProcessAlreadyExistsException;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessRef;
import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.Tenants;
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
 * Two-tenant isolation against the JDBC store (T18): two tenants may reuse the same business key
 * (the {@code (tenant_id, process_type, business_key)} unique key), and neither the start-time
 * lookup nor the read-side {@code findRef} may resolve the other tenant's instance.
 */
class MybatisProcessTenantIsolationTest {

  private static final ProcessBusinessKey SHARED = new ProcessBusinessKey("order-1");

  private JdbcTemplate jdbc;
  private ProcessStores stores;
  private DataSource dataSource;
  private DefaultProcessRuntime runtime;
  private DefaultProcessQuery query;
  private final AtomicInteger ids = new AtomicInteger();

  @BeforeEach
  void setUp() {
    dataSource =
        new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .addScript(
                "classpath:aipersimmon/db/migration/process-manager/h2/V1__aipersimmon_process_manager.sql")
            .addScript(
                "classpath:aipersimmon/db/migration/process-manager/h2/V2__drop_trace_id.sql")
            .addScript(
                "classpath:aipersimmon/db/migration/process-manager/h2/V3__add_tenant_id.sql")
            .addScript(
                "classpath:aipersimmon/db/migration/process-manager/h2/V4__parked_input_replay_marker.sql")
            .build();
    jdbc = new JdbcTemplate(dataSource);
    stores = ProcessStores.over(dataSource);
    var instances = stores.instances();
    var transitions = stores.transitions();
    var effects = stores.effects();
    var deadlines = stores.deadlines();
    Clock clock = Clock.fixed(Instant.parse("2026-07-24T00:00:00Z"), ZoneOffset.UTC);
    query = new DefaultProcessQuery(instances, transitions, effects, deadlines, clock);
    runtime =
        new DefaultProcessRuntime(
            instances,
            transitions,
            effects,
            deadlines,
            new ProcessDefinitionRegistry(List.of(new TestFulfilment.Definition())),
            new ProcessPayloadCodecRegistry(TestFulfilment.payloadCodecs()),
            new ProcessStateCodecRegistry(List.of(TestFulfilment.stateCodec())),
            new SpringTxProcessUnitOfWork(new DataSourceTransactionManager(dataSource)),
            clock,
            () -> "id-" + ids.incrementAndGet(),
            DuplicateBusinessKeyPolicy.REJECT,
            3);
  }

  private ProcessRef startFor(String tenant, String messageId) {
    return runtime
        .start(
            TestFulfilment.TYPE,
            SHARED,
            new TestFulfilment.Started("order-1"),
            CommandContext.root(Tenants.of(tenant), messageId))
        .processRef();
  }

  @Test
  void twoTenantsMayReuseABusinessKeyAndEachSeesOnlyItsOwnInstance() {
    ProcessRef acme = startFor("acme", "m-acme");
    ProcessRef globex = startFor("globex", "m-globex");

    // Distinct instances persisted under the same business key.
    assertNotEquals(acme.instanceId().value(), globex.instanceId().value());
    assertEquals(
        2L, jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_process_instance", Long.class));

    // The read side resolves only the ambient tenant's instance.
    Optional<ProcessRef> asAcme =
        TenantContext.runAs(Tenants.of("acme"), () -> query.findRef(TestFulfilment.TYPE, SHARED));
    Optional<ProcessRef> asGlobex =
        TenantContext.runAs(Tenants.of("globex"), () -> query.findRef(TestFulfilment.TYPE, SHARED));
    assertEquals(acme.instanceId().value(), asAcme.orElseThrow().instanceId().value());
    assertEquals(globex.instanceId().value(), asGlobex.orElseThrow().instanceId().value());

    // A tenant that never started this key sees nothing, even though two other tenants hold it.
    Optional<ProcessRef> asStranger =
        TenantContext.runAs(
            Tenants.of("initech"), () -> query.findRef(TestFulfilment.TYPE, SHARED));
    assertTrue(asStranger.isEmpty());
  }

  @Test
  void duplicateRejectionIsScopedToTheStartingTenant() {
    startFor("acme", "m-acme");
    // globex reusing the key is NOT a duplicate — it is a different tenant's first start.
    ProcessRef globex = startFor("globex", "m-globex");
    assertFalse(globex.instanceId().value().isBlank());

    // A genuine re-start under acme is rejected (its lookup finds acme's, not confused by globex).
    assertThrows(
        ProcessAlreadyExistsException.class,
        () ->
            runtime.start(
                TestFulfilment.TYPE,
                SHARED,
                new TestFulfilment.Started("order-1"),
                CommandContext.root(Tenants.of("acme"), "m-acme-2")));
  }
}
