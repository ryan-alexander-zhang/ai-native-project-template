package com.aipersimmon.ddd.processmanager.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.id.Uuidv7IdGenerator;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodecRegistry;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinitionRegistry;
import com.aipersimmon.ddd.processmanager.engine.runtime.DefaultProcessRuntime;
import com.aipersimmon.ddd.processmanager.engine.runtime.DuplicateBusinessKeyPolicy;
import com.aipersimmon.ddd.processmanager.engine.runtime.SpringTxProcessUnitOfWork;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessEffectStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessTransitionStore;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.runtime.ProcessAdvanceResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/**
 * The process-manager id supplier is where the decision expects the largest locality win: the
 * {@code process_instance} id is the random VARCHAR clustered primary key. This proves that when
 * the UUIDv7 {@link Uuidv7IdGenerator} supplies ids, a started instance's id is a time-ordered v7.
 * The instance id is opaque, so we assert only its UUID version — never any ordering.
 */
class JdbcProcessRuntimeUuidv7IdTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void startedInstanceIdIsUuidv7WhenSuppliedByTheGenerator() {
    DataSource dataSource =
        new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .addScript(
                "classpath:aipersimmon/db/migration/process-manager/h2/V1__aipersimmon_process_manager.sql")
            .addScript(
                "classpath:aipersimmon/db/migration/process-manager/h2/V2__drop_trace_id.sql")
            .addScript(
                "classpath:aipersimmon/db/migration/process-manager/h2/V3__add_tenant_id.sql")
            .build();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    DefaultProcessRuntime runtime =
        new DefaultProcessRuntime(
            new JdbcProcessInstanceStore(jdbc),
            new JdbcProcessTransitionStore(jdbc),
            new JdbcProcessEffectStore(jdbc),
            new JdbcProcessDeadlineStore(jdbc),
            new ProcessDefinitionRegistry(List.of(new TestFulfilment.Definition())),
            new ProcessPayloadCodecRegistry(TestFulfilment.payloadCodecs()),
            new ProcessStateCodecRegistry(List.of(TestFulfilment.stateCodec())),
            new SpringTxProcessUnitOfWork(new DataSourceTransactionManager(dataSource)),
            CLOCK,
            new Uuidv7IdGenerator()::newId,
            DuplicateBusinessKeyPolicy.REJECT,
            3);

    ProcessAdvanceResult started =
        runtime.start(
            TestFulfilment.TYPE,
            new ProcessBusinessKey("order-1"),
            new TestFulfilment.Started("order-1"),
            CommandContext.root("msg-start"));

    assertEquals(
        7,
        UUID.fromString(started.processRef().instanceId().value()).version(),
        "the process instance id is minted as a UUIDv7");
  }
}
