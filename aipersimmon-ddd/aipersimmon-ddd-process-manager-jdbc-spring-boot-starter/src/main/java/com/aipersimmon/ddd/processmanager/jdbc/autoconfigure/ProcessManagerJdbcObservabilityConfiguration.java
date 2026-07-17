package com.aipersimmon.ddd.processmanager.jdbc.autoconfigure;

import com.aipersimmon.ddd.processmanager.jdbc.observe.JdbcProcessBacklog;
import com.aipersimmon.ddd.processmanager.jdbc.observe.ProcessObserver;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Optional observability wiring, assembled after the core runtime. Metrics bind
 * only when Micrometer is on the classpath and a {@code MeterRegistry} is present; the health
 * indicator only when Actuator is on the classpath. Both read the pull-based
 * {@link JdbcProcessBacklog}; the {@link MicrometerProcessObserver} is registered as a
 * {@link ProcessObserver} so the runtime and relay pick up push-based latency and conflict meters.
 */
@AutoConfiguration(after = AipersimmonDddProcessManagerJdbcAutoConfiguration.class)
public class ProcessManagerJdbcObservabilityConfiguration {

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
        @ConditionalOnBean(JdbcProcessBacklog.class)
        @ConditionalOnMissingBean
        public ProcessManagerJdbcMeterBinder processManagerJdbcMeterBinder(
                JdbcProcessBacklog backlog, ProcessManagerJdbcProperties properties) {
            return new ProcessManagerJdbcMeterBinder(
                    backlog, properties.getObservability().getStuckThreshold());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(HealthIndicator.class)
    static class HealthConfiguration {

        @Bean
        @ConditionalOnBean(JdbcProcessBacklog.class)
        @ConditionalOnMissingBean(name = "processManagerJdbcHealthIndicator")
        public ProcessManagerJdbcHealthIndicator processManagerJdbcHealthIndicator(
                JdbcProcessBacklog backlog, ProcessManagerJdbcProperties properties) {
            ProcessManagerJdbcProperties.Observability cfg = properties.getObservability();
            return new ProcessManagerJdbcHealthIndicator(
                    backlog, cfg.getStuckThreshold(), cfg.getOldestPendingWarn());
        }
    }
}
