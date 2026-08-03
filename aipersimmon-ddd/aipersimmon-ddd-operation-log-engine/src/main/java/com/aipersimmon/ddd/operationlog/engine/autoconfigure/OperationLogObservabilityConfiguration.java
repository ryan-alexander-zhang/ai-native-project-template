package com.aipersimmon.ddd.operationlog.engine.autoconfigure;

import com.aipersimmon.ddd.operationlog.engine.observability.OperationLogMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Optional metrics wiring. Ordered <em>before</em> the engine's auto-configuration because that is
 * where the no-op {@link OperationLogMetrics} fallback is declared under
 * {@code @ConditionalOnMissingBean} — the Micrometer bridge must already be registered when the
 * fallback is evaluated, or the fallback wins and the bridge backs off. A consumer-supplied {@link
 * OperationLogMetrics} bean still overrides both.
 */
@AutoConfiguration(before = AipersimmonDddOperationLogAutoConfiguration.class)
public class OperationLogObservabilityConfiguration {

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(MeterRegistry.class)
  @ConditionalOnBean(MeterRegistry.class)
  static class MetricsConfiguration {

    @Bean
    @ConditionalOnMissingBean(OperationLogMetrics.class)
    public MicrometerOperationLogMetrics micrometerOperationLogMetrics(MeterRegistry registry) {
      return new MicrometerOperationLogMetrics(registry);
    }
  }
}
