package com.aipersimmon.ddd.processmanager.engine.autoconfigure;

import com.aipersimmon.ddd.processmanager.engine.observe.ProcessBacklog;
import com.aipersimmon.ddd.processmanager.engine.observe.ProcessObserver;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Optional observability wiring, assembled after the core runtime. Metrics bind only when
 * Micrometer is on the classpath and a {@code MeterRegistry} is present; the health indicator only
 * when Actuator is on the classpath. Both read the pull-based {@link ProcessBacklog}; the {@link
 * MicrometerProcessObserver} is registered as a {@link ProcessObserver} so the runtime and relay
 * pick up push-based latency and conflict meters.
 */
@AutoConfiguration(after = AipersimmonDddProcessManagerAutoConfiguration.class)
public class ProcessManagerObservabilityConfiguration {

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(MeterRegistry.class)
  @ConditionalOnBean(MeterRegistry.class)
  static class MetricsConfiguration {

    @Bean
    @ConditionalOnMissingBean(ProcessObserver.class)
    public MicrometerProcessObserver micrometerProcessObserver(MeterRegistry registry) {
      return new MicrometerProcessObserver(registry);
    }

    @Bean
    @ConditionalOnBean(ProcessBacklog.class)
    @ConditionalOnMissingBean
    public ProcessManagerMeterBinder processManagerMeterBinder(
        ProcessBacklog backlog,
        ProcessManagerProperties properties,
        java.time.Clock processManagerClock) {
      return new ProcessManagerMeterBinder(
          backlog, properties.getObservability().getStuckThreshold(), processManagerClock);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(HealthIndicator.class)
  static class HealthConfiguration {

    @Bean
    @ConditionalOnBean(ProcessBacklog.class)
    @ConditionalOnMissingBean(name = "processManagerHealthIndicator")
    public ProcessManagerHealthIndicator processManagerHealthIndicator(
        ProcessBacklog backlog, ProcessManagerProperties properties) {
      ProcessManagerProperties.Observability cfg = properties.getObservability();
      return new ProcessManagerHealthIndicator(
          backlog, cfg.getStuckThreshold(), cfg.getOldestPendingWarn());
    }
  }
}
