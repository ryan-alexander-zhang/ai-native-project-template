package com.example.samples.s04;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandInterceptor;
import com.aipersimmon.ddd.tenancy.TenantContext;
import io.opentelemetry.api.trace.Span;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.MDC;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;

/**
 * What was true inside the command the inbound adapter issued: the causal ids it inherited from the
 * envelope, the tenant the bridge bound, the MDC, and the trace the record arrived on.
 *
 * <p>This is the only vantage point from which the consuming half of S13 and S15 can be asserted. The
 * database shows the effect but not the metadata; the topic shows the metadata but not what the
 * application made of it.
 */
@TestConfiguration(proxyBeanMethods = false)
class Probes {

  record Handled(
      String commandType,
      CommandContext context,
      String ambientTenant,
      String mdcCorrelationId,
      String mdcTenant,
      String mdcTraceId,
      String activeTraceId) {}

  static final class Recorder {
    private final List<Handled> handled = new CopyOnWriteArrayList<>();

    List<Handled> all() {
      return List.copyOf(handled);
    }

    Handled only() {
      if (handled.size() != 1) {
        throw new AssertionError("expected exactly one handled command, got " + handled);
      }
      return handled.get(0);
    }

    void clear() {
      handled.clear();
    }
  }

  @Bean
  Recorder handledCommandRecorder() {
    return new Recorder();
  }

  @Bean
  @Order(400)
  CommandInterceptor recordAmbientState(Recorder recorder) {
    return new CommandInterceptor() {
      @Override
      public <R> R intercept(Command<R> command, CommandContext context, Invocation<R> invocation) {
        recorder.handled.add(
            new Handled(
                command.getClass().getSimpleName(),
                context,
                TenantContext.effective().value(),
                MDC.get("correlationId"),
                MDC.get("tenant"),
                MDC.get("trace_id"),
                Span.current().getSpanContext().getTraceId()));
        return invocation.proceed();
      }

      @Override
      public int order() {
        return 400;
      }
    };
  }
}
