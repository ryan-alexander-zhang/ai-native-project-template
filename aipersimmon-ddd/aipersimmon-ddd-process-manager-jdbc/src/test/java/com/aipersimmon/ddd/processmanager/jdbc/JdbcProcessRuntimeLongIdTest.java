package com.aipersimmon.ddd.processmanager.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aipersimmon.ddd.cqrs.CommandContext;
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
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/**
 * The id columns must hold any legal id (up to the 96-char id-column width), not just short UUIDs.
 */
class JdbcProcessRuntimeLongIdTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneOffset.UTC);

  private JdbcTemplate jdbc;
  private DefaultProcessRuntime runtime;
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
            () -> "id-" + ids.incrementAndGet(),
            DuplicateBusinessKeyPolicy.REJECT,
            3);
  }

  @Test
  void acceptsAMessageIdUpToTheIdColumnWidth() {
    ProcessAdvanceResult started =
        runtime.start(
            TestFulfilment.TYPE,
            new ProcessBusinessKey("order-1"),
            new TestFulfilment.Started("order-1"),
            CommandContext.root("msg-start"));

    // A no-effect transition (Finish) whose input carries an id longer than the old 64-char column
    // width but within the 96-char id-column width; only input_message_id is exercised.
    String longMessageId = "m".repeat(80);
    CommandContext cause = new CommandContext(longMessageId, "corr-1", null);
    ProcessAdvanceResult finished =
        runtime.handle(started.processRef(), new TestFulfilment.Finish(), cause);

    assertEquals(
        longMessageId,
        jdbc.queryForObject(
            "SELECT input_message_id FROM aipersimmon_process_transition WHERE transition_id = ?",
            String.class,
            finished.transitionId()));
  }
}
