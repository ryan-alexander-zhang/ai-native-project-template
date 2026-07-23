package com.aipersimmon.ddd.operationlog.engine.autoconfigure;

import com.aipersimmon.ddd.operationlog.engine.classifier.DefaultFailureClassifier;
import com.aipersimmon.ddd.operationlog.engine.observability.OperationLogMetrics;
import com.aipersimmon.ddd.operationlog.engine.pipeline.DefaultOperationLogs;
import com.aipersimmon.ddd.operationlog.engine.pipeline.OperationLogLimits;
import com.aipersimmon.ddd.operationlog.port.OperationLogSink;
import com.aipersimmon.ddd.operationlog.port.OperationLogs;
import com.aipersimmon.ddd.operationlog.spi.FailureClassifier;
import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Wires the storage-agnostic Operation Log engine: a UTC clock, the default {@link
 * FailureClassifier}, and the default {@link OperationLogs} pipeline (which binds only once a
 * storage backend has contributed an {@link OperationLogSink}). Every bean is conditional so an
 * application can override any of them.
 */
@AutoConfiguration
@EnableConfigurationProperties(OperationLogProperties.class)
public class AipersimmonDddOperationLogAutoConfiguration {

  /** UTC clock, name-scoped so multiple components can each contribute one and inject by name. */
  @Bean
  @ConditionalOnMissingBean(name = "operationLogClock")
  public Clock operationLogClock() {
    return Clock.systemUTC();
  }

  /** The default failure classifier; replaced by a consumer bean when present. */
  @Bean
  @ConditionalOnMissingBean(FailureClassifier.class)
  public FailureClassifier operationLogFailureClassifier() {
    return new DefaultFailureClassifier();
  }

  /**
   * The default metrics seam: no-op. A consumer that wants metrics supplies a bean bridging {@link
   * OperationLogMetrics} to its meter registry (Micrometer, OpenTelemetry, …); this component keeps
   * no compile dependency on any of them.
   */
  @Bean
  @ConditionalOnMissingBean(OperationLogMetrics.class)
  public OperationLogMetrics operationLogMetrics() {
    return OperationLogMetrics.noOp();
  }

  /**
   * The default pipeline. Binds only when a backend has provided an {@link OperationLogSink}. The
   * id supplier defaults to a random UUID; inject a time-ordered supplier (ULID/UUIDv7) for better
   * index locality.
   */
  @Bean
  @ConditionalOnBean(OperationLogSink.class)
  @ConditionalOnMissingBean(OperationLogs.class)
  public OperationLogs operationLogs(
      OperationLogSink sink,
      Clock operationLogClock,
      OperationLogProperties properties,
      OperationLogMetrics metrics) {
    OperationLogProperties.Limits configured = properties.getLimits();
    OperationLogLimits limits =
        new OperationLogLimits(
            configured.getSummaryMaxChars(),
            configured.getMaxChanges(),
            configured.getMaxDetails(),
            configured.getMaxValueChars());
    return new DefaultOperationLogs(
        sink, operationLogClock, () -> UUID.randomUUID().toString(), limits, metrics);
  }
}
