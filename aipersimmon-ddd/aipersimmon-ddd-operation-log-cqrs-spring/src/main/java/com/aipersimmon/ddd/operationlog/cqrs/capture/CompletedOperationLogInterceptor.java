package com.aipersimmon.ddd.operationlog.cqrs.capture;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandInterceptor;
import com.aipersimmon.ddd.operationlog.definition.OperationLogDefinition;
import com.aipersimmon.ddd.operationlog.definition.PreparedOperationLog;
import com.aipersimmon.ddd.operationlog.engine.observability.OperationLogMetrics;
import com.aipersimmon.ddd.operationlog.model.OperationLogDraft;
import com.aipersimmon.ddd.operationlog.model.OperationLogInvocation;
import com.aipersimmon.ddd.operationlog.port.OperationLogs;
import java.util.Objects;
import java.util.Optional;

/**
 * Records the successful / committed-rejected outcome. Ordered inside the transaction and around
 * the handler ({@code ORDER = 250 > 200}), it captures the before projection in {@code prepare},
 * lets the handler run, classifies the result in {@code complete}, and appends in the same business
 * transaction. An append failure propagates so the transaction rolls back fail-closed. The template
 * render time (prepare + complete, excluding the handler) is reported to {@link
 * OperationLogMetrics}.
 */
public final class CompletedOperationLogInterceptor implements CommandInterceptor {

  /** Inside the transaction boundary (200), wrapping the handler. */
  public static final int ORDER = 250;

  private final OperationLogDefinitionRegistry registry;
  private final OperationLogInvocationFactory invocationFactory;
  private final OperationLogs operationLogs;
  private final OperationLogMetrics metrics;

  /** Builds an interceptor with no-op metrics. */
  public CompletedOperationLogInterceptor(
      OperationLogDefinitionRegistry registry,
      OperationLogInvocationFactory invocationFactory,
      OperationLogs operationLogs) {
    this(registry, invocationFactory, operationLogs, OperationLogMetrics.noOp());
  }

  public CompletedOperationLogInterceptor(
      OperationLogDefinitionRegistry registry,
      OperationLogInvocationFactory invocationFactory,
      OperationLogs operationLogs,
      OperationLogMetrics metrics) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.invocationFactory = Objects.requireNonNull(invocationFactory, "invocationFactory");
    this.operationLogs = Objects.requireNonNull(operationLogs, "operationLogs");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  @Override
  public <R> R intercept(Command<R> command, CommandContext context, Invocation<R> invocation) {
    Optional<OperationLogDefinition<Object, Object>> definition = registry.find(command.getClass());
    if (definition.isEmpty()) {
      return invocation.proceed();
    }
    OperationLogInvocation logInvocation = invocationFactory.create(context);
    long prepareStart = System.nanoTime();
    PreparedOperationLog<Object> prepared = definition.get().prepare(command, logInvocation);
    long renderNanos = System.nanoTime() - prepareStart;
    R result = invocation.proceed();
    long completeStart = System.nanoTime();
    Optional<OperationLogDraft> draft = prepared.complete(result);
    renderNanos += System.nanoTime() - completeStart;
    long totalRenderNanos = renderNanos;
    draft.ifPresent(
        d -> {
          metrics.renderLatencyNanos(d.operationCode(), totalRenderNanos);
          operationLogs.record(d);
        });
    return result;
  }

  @Override
  public int order() {
    return ORDER;
  }
}
