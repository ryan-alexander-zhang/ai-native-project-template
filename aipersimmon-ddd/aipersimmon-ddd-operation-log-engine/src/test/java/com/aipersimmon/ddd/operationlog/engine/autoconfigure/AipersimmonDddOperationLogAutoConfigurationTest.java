package com.aipersimmon.ddd.operationlog.engine.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.operationlog.engine.observability.OperationLogMetrics;
import com.aipersimmon.ddd.operationlog.model.OperationLogEntry;
import com.aipersimmon.ddd.operationlog.port.AppendResult;
import com.aipersimmon.ddd.operationlog.port.OperationLogSink;
import com.aipersimmon.ddd.operationlog.port.OperationLogs;
import com.aipersimmon.ddd.operationlog.spi.FailureClassifier;
import java.time.Clock;
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
              AutoConfigurations.of(AipersimmonDddOperationLogAutoConfiguration.class));

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
            "aipersimmon.ddd.operation-log.tenant.enabled=true",
            "aipersimmon.ddd.operation-log.limits.summary-max-chars=50",
            "aipersimmon.ddd.operation-log.limits.max-changes=3",
            "aipersimmon.ddd.operation-log.limits.max-details=4",
            "aipersimmon.ddd.operation-log.limits.max-value-chars=7")
        .run(
            context -> {
              assertNotNull(context.getBean(OperationLogs.class));
              OperationLogProperties props = context.getBean(OperationLogProperties.class);
              assertEquals("svc", props.getSource());
              assertTrue(props.getTenant().isEnabled());
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
          assertFalse(props.getTenant().isEnabled());
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
}
