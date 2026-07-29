package com.aipersimmon.ddd.outbox.engine.autoconfigure;

import com.aipersimmon.ddd.outbox.engine.observe.OutboxBacklog;
import com.aipersimmon.ddd.outbox.engine.observe.OutboxObserver;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds Micrometer to the outbox's observability seams, assembled after the outbox runtime — which
 * is where the framework-free {@link OutboxBacklog} read itself is registered, so it already exists
 * when these conditions are evaluated.
 *
 * <p>Everything Micrometer lives in a nested class guarded by {@link ConditionalOnClass}, so an
 * application without Micrometer never has this class introspected for types it does not have.
 * Putting these beans directly on the enclosing class fails every such context with a {@code
 * NoClassDefFoundError} while Spring deduces bean types.
 *
 * <p>There is deliberately no health indicator. A relay that cannot reach its broker is not a sick
 * instance: flipping the pod DOWN would take it out of service for a problem restarting it cannot
 * fix, and this very framework has already been bitten once by a health check stuck DEGRADED on a
 * backlog nothing was draining. The backlog age is a gauge to alert a human on, not a reason to
 * recycle a pod.
 */
@AutoConfiguration(after = AipersimmonDddOutboxEngineAutoConfiguration.class)
public class OutboxObservabilityConfiguration {

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(MeterRegistry.class)
  @ConditionalOnBean(MeterRegistry.class)
  static class MetricsConfiguration {

    @Bean
    @ConditionalOnMissingBean(OutboxObserver.class)
    public MicrometerOutboxObserver micrometerOutboxObserver(MeterRegistry registry) {
      return new MicrometerOutboxObserver(registry);
    }

    @Bean
    @ConditionalOnBean(OutboxBacklog.class)
    @ConditionalOnMissingBean
    public OutboxMeterBinder outboxMeterBinder(OutboxBacklog outboxBacklog, Clock outboxClock) {
      return new OutboxMeterBinder(outboxBacklog, outboxClock);
    }
  }
}
