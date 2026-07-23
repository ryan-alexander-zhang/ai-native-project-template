package com.aipersimmon.ddd.operationlog.cqrs.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.aipersimmon.ddd.cqrs.CommandInterceptor;
import com.aipersimmon.ddd.cqrs.spring.RegistryCommandBus;
import com.aipersimmon.ddd.cqrs.spring.TransactionCommandInterceptor;
import com.aipersimmon.ddd.cqrs.spring.TransactionTemplateUnitOfWork;
import com.aipersimmon.ddd.operationlog.annotation.OperationLog;
import com.aipersimmon.ddd.operationlog.engine.classifier.DefaultFailureClassifier;
import com.aipersimmon.ddd.operationlog.engine.pipeline.DefaultOperationLogs;
import com.aipersimmon.ddd.operationlog.engine.pipeline.OperationLogLimits;
import com.aipersimmon.ddd.operationlog.jdbc.JdbcOperationLogSink;
import com.aipersimmon.ddd.operationlog.model.Actor;
import com.aipersimmon.ddd.operationlog.port.OperationLogs;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * End-to-end capture scenarios exercised against a real database and the real command pipeline
 * (RegistryCommandBus + the built-in TransactionCommandInterceptor + the two operation-log
 * interceptors + the JDBC sink). Reused for H2 and PostgreSQL to prove backend/dialect equivalence.
 */
public final class OperationLogEndToEndScenarios {

  private OperationLogEndToEndScenarios() {}

  public static void run(DataSource dataSource, String dialectDirectory) {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    DatabasePopulatorUtils.execute(
        new ResourceDatabasePopulator(
            new ClassPathResource(
                "aipersimmon/db/migration/operation-log/"
                    + dialectDirectory
                    + "/V1__aipersimmon_operation_log.sql")),
        dataSource);
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS demo_resource (id VARCHAR(64) PRIMARY KEY, val VARCHAR(255))");
    jdbc.update("DELETE FROM aipersimmon_operation_log");
    jdbc.update("DELETE FROM demo_resource");

    CommandBus bus = buildBus(jdbc, dataSource);

    // 1. success: business row and SUCCEEDED+COMMITTED log commit together.
    bus.send(new UpdateResource("res-1", "hello", false));
    assertEquals(1, businessCount(jdbc, "res-1"));
    assertEquals(1, logCount(jdbc, "res-1"));
    assertEquals("SUCCEEDED", outcome(jdbc, "res-1"));
    assertEquals("COMMITTED", completion(jdbc, "res-1"));

    // 2. normal-return rejection (rejectedWhen): REJECTED + COMMITTED, business committed.
    bus.send(new UpdateResource("res-2", "x", true));
    assertEquals(1, businessCount(jdbc, "res-2"));
    assertEquals("REJECTED", outcome(jdbc, "res-2"));
    assertEquals("COMMITTED", completion(jdbc, "res-2"));

    // 3. handler failure: business rolled back, FAILED + ROLLED_BACK logged in an independent tx.
    assertThrows(IllegalStateException.class, () -> bus.send(new FailingUpdate("res-3")));
    assertEquals(0, businessCount(jdbc, "res-3"));
    assertEquals(1, logCount(jdbc, "res-3"));
    assertEquals("FAILED", outcome(jdbc, "res-3"));
    assertEquals("ROLLED_BACK", completion(jdbc, "res-3"));

    // 4. idempotent redelivery (same messageId, same result kind): exactly one log row.
    CommandContext redelivered = CommandContext.root("msg-9");
    bus.sendAs(new UpdateResource("res-4", "a", false), redelivered);
    bus.sendAs(new UpdateResource("res-4", "a", false), redelivered);
    assertEquals(1, logCount(jdbc, "res-4"));
    assertEquals(1, businessCount(jdbc, "res-4"));
  }

  private static CommandBus buildBus(JdbcTemplate jdbc, DataSource dataSource) {
    DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);
    OperationLogs operationLogs =
        new DefaultOperationLogs(
            JdbcOperationLogSink.create(jdbc, dataSource, new ObjectMapper()),
            Clock.systemUTC(),
            () -> UUID.randomUUID().toString(),
            OperationLogLimits.defaults());
    OperationLogDefinitionRegistry registry =
        OperationLogDefinitionRegistry.build(
            List.of(),
            Map.of(
                UpdateResource.class, annotation(UpdateResource.class),
                FailingUpdate.class, annotation(FailingUpdate.class)));
    OperationLogInvocationFactory factory =
        new OperationLogInvocationFactory(
            "orders-service", Clock.systemUTC(), () -> Actor.user("u1", "Alice"), () -> "acme");
    CommandInterceptor completed =
        new CompletedOperationLogInterceptor(registry, factory, operationLogs);
    CommandInterceptor failed =
        new FailedOperationLogInterceptor(
            registry,
            factory,
            operationLogs,
            new DefaultFailureClassifier(),
            new DefaultFailureCompletionPolicy(),
            TransactionSynchronizationManager::isActualTransactionActive,
            new SpringIndependentTransactionRunner(txManager));
    CommandInterceptor transaction =
        new TransactionCommandInterceptor(
            new TransactionTemplateUnitOfWork(new TransactionTemplate(txManager)));
    return new RegistryCommandBus(
        List.of(new UpdateResourceHandler(jdbc), new FailingUpdateHandler(jdbc)),
        List.of(failed, transaction, completed));
  }

  private static AnnotationOperationLogDefinition annotation(Class<?> commandType) {
    return AnnotationOperationLogDefinition.compile(commandType.getAnnotation(OperationLog.class));
  }

  private static int logCount(JdbcTemplate jdbc, String targetId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM aipersimmon_operation_log WHERE target_id = ?",
        Integer.class,
        targetId);
  }

  private static int businessCount(JdbcTemplate jdbc, String id) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM demo_resource WHERE id = ?", Integer.class, id);
  }

  private static String outcome(JdbcTemplate jdbc, String targetId) {
    return jdbc.queryForObject(
        "SELECT outcome FROM aipersimmon_operation_log WHERE target_id = ?",
        String.class,
        targetId);
  }

  private static String completion(JdbcTemplate jdbc, String targetId) {
    return jdbc.queryForObject(
        "SELECT completion FROM aipersimmon_operation_log WHERE target_id = ?",
        String.class,
        targetId);
  }

  @OperationLog(
      code = "resource.update",
      targetType = "Resource",
      targetId = "${input.resourceId}",
      success = "set to ${input.value}",
      failure = "update failed",
      rejectedWhen = "${resultProjection.rejected}")
  public record UpdateResource(String resourceId, String value, boolean rejectResult)
      implements Command<ResourceView> {}

  @OperationLog(
      code = "resource.fail",
      targetType = "Resource",
      targetId = "${input.resourceId}",
      success = "ok",
      failure = "update failed")
  public record FailingUpdate(String resourceId) implements Command<Void> {}

  public record ResourceView(boolean rejected) {}

  static final class UpdateResourceHandler implements CommandHandler<UpdateResource, ResourceView> {
    private final JdbcTemplate jdbc;

    UpdateResourceHandler(JdbcTemplate jdbc) {
      this.jdbc = jdbc;
    }

    @Override
    public ResourceView handle(UpdateResource command, CommandContext context) {
      jdbc.update("DELETE FROM demo_resource WHERE id = ?", command.resourceId());
      jdbc.update(
          "INSERT INTO demo_resource (id, val) VALUES (?, ?)",
          command.resourceId(),
          command.value());
      return new ResourceView(command.rejectResult());
    }
  }

  static final class FailingUpdateHandler implements CommandHandler<FailingUpdate, Void> {
    private final JdbcTemplate jdbc;

    FailingUpdateHandler(JdbcTemplate jdbc) {
      this.jdbc = jdbc;
    }

    @Override
    public Void handle(FailingUpdate command, CommandContext context) {
      jdbc.update(
          "INSERT INTO demo_resource (id, val) VALUES (?, ?)", command.resourceId(), "partial");
      throw new IllegalStateException("boom");
    }
  }
}
