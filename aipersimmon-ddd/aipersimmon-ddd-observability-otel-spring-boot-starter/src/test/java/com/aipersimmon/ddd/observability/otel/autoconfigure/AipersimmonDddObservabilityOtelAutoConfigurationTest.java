package com.aipersimmon.ddd.observability.otel.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipersimmon.ddd.observability.Tracer;
import com.aipersimmon.ddd.observability.Tracer.SpanScope;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Verifies the auto-configuration wires the tracer and command-span interceptor when an
 * {@code OpenTelemetry} bean is present (supplied by the OTEL starter in a real app), and
 * that a consumer-provided tracer overrides the default.
 */
class AipersimmonDddObservabilityOtelAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AipersimmonDddObservabilityOtelAutoConfiguration.class));

    @Test
    void wiresTracerAndCommandInterceptorWhenOpenTelemetryPresent() {
        runner.withUserConfiguration(OpenTelemetryConfig.class).run(context -> {
            assertThat(context).hasSingleBean(Tracer.class);
            assertThat(context).hasSingleBean(TracingCommandInterceptor.class);
        });
    }

    @Test
    void backsOffWhenConsumerProvidesTracer() {
        runner.withUserConfiguration(OpenTelemetryConfig.class, CustomTracerConfig.class).run(context -> {
            assertThat(context).hasSingleBean(Tracer.class);
            assertThat(context.getBean(Tracer.class)).isSameAs(CustomTracerConfig.CUSTOM);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class OpenTelemetryConfig {
        @Bean
        OpenTelemetry openTelemetry() {
            return OpenTelemetrySdk.builder()
                    .setTracerProvider(SdkTracerProvider.builder().build())
                    .build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomTracerConfig {
        static final Tracer CUSTOM = name -> new Tracer.SpanScope() {
            @Override
            public SpanScope attribute(String key, String value) {
                return this;
            }

            @Override
            public SpanScope attribute(String key, long value) {
                return this;
            }

            @Override
            public SpanScope error(Throwable error) {
                return this;
            }

            @Override
            public void close() {
            }
        };

        @Bean
        Tracer tracer() {
            return CUSTOM;
        }
    }
}
