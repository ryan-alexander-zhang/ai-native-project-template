package com.aipersimmon.ddd.observability.otel.autoconfigure;

import com.aipersimmon.ddd.observability.StoreAndForwardTracer;
import com.aipersimmon.ddd.observability.Tracer;
import com.aipersimmon.ddd.observability.otel.OpenTelemetryStoreAndForwardTracer;
import com.aipersimmon.ddd.observability.otel.OpenTelemetryTracer;
import io.opentelemetry.api.OpenTelemetry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Binds the framework-free observability SPIs to OpenTelemetry and registers the
 * domain-spine spans. The {@code OpenTelemetry} bean and boundary auto-instrumentation
 * come from the OpenTelemetry Spring Boot starter this module depends on; here we only
 * add the spans that auto-instrumentation cannot see (the command span, for now).
 *
 * <p>The {@code OpenTelemetry} instance is injected as a bean-method parameter (resolved
 * at instantiation, after all bean definitions are registered), so no auto-configuration
 * ordering is needed. Every bean is {@link ConditionalOnMissingBean}, so a consumer can
 * override the tracer or the interceptor.
 */
@AutoConfiguration
@ConditionalOnClass({OpenTelemetry.class, com.aipersimmon.ddd.cqrs.CommandInterceptor.class})
public class AipersimmonDddObservabilityOtelAutoConfiguration {

    /** Instrumentation scope name for the library's own spans. */
    static final String INSTRUMENTATION_SCOPE = "com.aipersimmon.ddd";

    @Bean
    @ConditionalOnMissingBean
    Tracer aipersimmonDomainTracer(OpenTelemetry openTelemetry) {
        return new OpenTelemetryTracer(openTelemetry.getTracer(INSTRUMENTATION_SCOPE));
    }

    @Bean
    @ConditionalOnMissingBean
    StoreAndForwardTracer aipersimmonStoreAndForwardTracer(OpenTelemetry openTelemetry) {
        return new OpenTelemetryStoreAndForwardTracer(
                openTelemetry.getTracer(INSTRUMENTATION_SCOPE),
                openTelemetry.getPropagators().getTextMapPropagator());
    }

    @Bean
    @ConditionalOnMissingBean
    TracingCommandInterceptor tracingCommandInterceptor(Tracer tracer) {
        return new TracingCommandInterceptor(tracer);
    }
}
