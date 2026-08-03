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
 * What was true <em>inside</em> a command handler: the context, the ambient tenant, the MDC, and the
 * active trace.
 *
 * <p>Every claim S13 and S15 make about propagation is a claim about that moment, and none of it is
 * visible from outside the process — the tenant is ambient, the MDC is thread-local, and the trace id
 * only reaches storage in an opaque header. Recording it from the innermost position in the
 * interceptor chain is the only honest way to assert it.
 */
@TestConfiguration(proxyBeanMethods = false)
class Probes {

  /** One command's ambient state, as seen from inside the handler. */
  record Handled(
      String commandType,
      CommandContext context,
      String ambientTenant,
      String mdcCorrelationId,
      String mdcTenant,
      String mdcRequestId,
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

  /**
   * Innermost (order 400): inside tracing (-100), the tenant binding (-90), logging (0) and the
   * transaction (200), so everything those install is in place.
   */
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
                MDC.get("requestId"),
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
