package com.aipersimmon.ddd.observability.otel.autoconfigure;

import com.aipersimmon.ddd.observability.StoreAndForwardTracer;
import com.aipersimmon.ddd.observability.Tracer;
import com.aipersimmon.ddd.observability.otel.OpenTelemetryStoreAndForwardTracer;
import com.aipersimmon.ddd.observability.otel.OpenTelemetryTracer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.filter.OncePerRequestFilter;

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

    /**
     * Puts the real trace id on the MDC for each request so the web error body can surface it.
     * Only in a servlet web app with {@code OncePerRequestFilter} on the classpath.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(OncePerRequestFilter.class)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    TraceIdMdcFilter aipersimmonTraceIdMdcFilter() {
        return new TraceIdMdcFilter();
    }

    /**
     * Installs the SDK {@link OpenTelemetry} instance into the logback
     * {@link OpenTelemetryAppender}, so an application whose {@code logback-spring.xml} attaches
     * that appender emits each log line as an OTLP {@code LogRecord} — stamped with the active
     * {@code trace_id}/{@code span_id}, giving logs↔traces correlation in the backend (SigNoz).
     * Without this call the appender has no SDK and drops records. Idempotent, and harmless when
     * no app actually attaches the appender; guarded by {@link ConditionalOnClass} so a build that
     * excludes the appender still starts.
     */
    @Bean
    @ConditionalOnClass(OpenTelemetryAppender.class)
    InitializingBean aipersimmonOpenTelemetryAppenderInstaller(OpenTelemetry openTelemetry) {
        return () -> OpenTelemetryAppender.install(openTelemetry);
    }
}
