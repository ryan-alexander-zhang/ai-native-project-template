package com.aipersimmon.ddd.operationlog.engine.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.core.id.IdGenerator;
import com.aipersimmon.ddd.id.AipersimmonDddIdAutoConfiguration;
import com.aipersimmon.ddd.operationlog.engine.observability.OperationLogMetrics;
import com.aipersimmon.ddd.operationlog.model.Actor;
import com.aipersimmon.ddd.operationlog.model.Causality;
import com.aipersimmon.ddd.operationlog.model.OperationLogDraft;
import com.aipersimmon.ddd.operationlog.model.OperationLogEntry;
import com.aipersimmon.ddd.operationlog.model.OperationLogInvocation;
import com.aipersimmon.ddd.operationlog.port.AppendResult;
import com.aipersimmon.ddd.operationlog.port.OperationLogSink;
import com.aipersimmon.ddd.operationlog.port.OperationLogs;
import com.aipersimmon.ddd.operationlog.spi.FailureClassifier;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class AipersimmonDddOperationLogAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  AipersimmonDddOperationLogAutoConfiguration.class,
                  // The record-id supplier requires an IdGenerator (issue-00053), so the module
                  // that supplies it is part of the minimal assembly.
                  AipersimmonDddIdAutoConfiguration.class));

  @Test
  void wires_clock_classifier_and_noop_metrics_but_no_pipeline_without_a_sink() {
    runner.run(
        context -> {
          assertNotNull(context.getBean("operationLogClock", Clock.class));
          assertNotNull(context.getBean(FailureClassifier.class));
          assertSame(OperationLogMetrics.noOp(), context.getBean(OperationLogMetrics.class));
          assertThrows(
              NoSuchBeanDefinitionException.class, () -> context.getBean(OperationLogs.class));
        });
  }

  @Test
  void binds_the_pipeline_once_a_sink_is_present_and_reads_configured_limits() {
    runner
        .withUserConfiguration(SinkConfig.class)
        .withPropertyValues(
            "aipersimmon.ddd.operation-log.source=svc",
            "aipersimmon.ddd.operation-log.limits.summary-max-chars=50",
            "aipersimmon.ddd.operation-log.limits.max-changes=3",
            "aipersimmon.ddd.operation-log.limits.max-details=4",
            "aipersimmon.ddd.operation-log.limits.max-value-chars=7")
        .run(
            context -> {
              assertNotNull(context.getBean(OperationLogs.class));
              OperationLogProperties props = context.getBean(OperationLogProperties.class);
              assertEquals("svc", props.getSource());
              assertEquals(50, props.getLimits().getSummaryMaxChars());
              assertEquals(3, props.getLimits().getMaxChanges());
              assertEquals(4, props.getLimits().getMaxDetails());
              assertEquals(7, props.getLimits().getMaxValueChars());
            });
  }

  @Test
  void defaults_are_exposed_when_unset() {
    runner.run(
        context -> {
          OperationLogProperties props = context.getBean(OperationLogProperties.class);
          assertEquals("", props.getSource());
          assertEquals(1024, props.getLimits().getSummaryMaxChars());
        });
  }

  @Test
  void user_beans_win_over_the_defaults() {
    FailureClassifier customClassifier = (throwable, invocation) -> null;
    OperationLogMetrics customMetrics = OperationLogMetrics.noOp();
    runner
        .withBean(FailureClassifier.class, () -> customClassifier)
        .withBean("customMetrics", OperationLogMetrics.class, () -> customMetrics)
        .run(
            context -> {
              assertSame(customClassifier, context.getBean(FailureClassifier.class));
              assertSame(customMetrics, context.getBean(OperationLogMetrics.class));
            });
  }

  @Test
  void record_id_comes_from_the_injected_id_generator() {
    // The record_id DDL documents a time-ordered id; the pipeline mints it from the IdGenerator
    // bean when present. A sentinel proves it flows from the bean rather than an inlined random
    // UUID.
    CapturingSink sink = new CapturingSink();
    IdGenerator idGenerator = () -> "record-id-sentinel";
    runner
        .withBean(OperationLogSink.class, () -> sink)
        .withBean(IdGenerator.class, () -> idGenerator)
        .run(
            context -> {
              context
                  .getBean(OperationLogs.class)
                  .record(
                      OperationLogDraft.from(
                              OperationLogInvocation.builder()
                                  .source("orders-service")
                                  .tenant("acme")
                                  .actor(Actor.user("u1", "Alice"))
                                  .causality(Causality.none())
                                  .occurredAt(Instant.parse("2020-01-01T00:00:00Z"))
                                  .build())
                          .operation("order.remark.update")
                          .target("Order", "o1", "SO-1")
                          .succeeded()
                          .summary("ok")
                          .build());
              assertEquals("record-id-sentinel", sink.captured.recordId());
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class SinkConfig {
    @Bean
    OperationLogSink sink() {
      return new RecordingSink();
    }
  }

  private static final class RecordingSink implements OperationLogSink {
    @Override
    public AppendResult append(OperationLogEntry entry) {
      return new AppendResult.Appended(entry.recordId());
    }
  }

  private static final class CapturingSink implements OperationLogSink {
    private OperationLogEntry captured;

    @Override
    public AppendResult append(OperationLogEntry entry) {
      this.captured = entry;
      return new AppendResult.Appended(entry.recordId());
    }
  }
}
