package com.aipersimmon.ddd.operationlog.cqrs.capture;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandInterceptor;
import com.aipersimmon.ddd.operationlog.definition.OperationLogDefinition;
import com.aipersimmon.ddd.operationlog.engine.observability.OperationLogMetrics;
import com.aipersimmon.ddd.operationlog.model.Completion;
import com.aipersimmon.ddd.operationlog.model.OperationLogDraft;
import com.aipersimmon.ddd.operationlog.model.OperationLogInvocation;
import com.aipersimmon.ddd.operationlog.model.OperationResult;
import com.aipersimmon.ddd.operationlog.port.OperationLogs;
import com.aipersimmon.ddd.operationlog.spi.ClassifiedOutcome;
import com.aipersimmon.ddd.operationlog.spi.FailureClassifier;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records the failure outcome. Ordered outside concurrency translation, validation, and the
 * transaction ({@code ORDER = 25 < 50}), so it observes the translated domain exception, validation
 * rejections (NOT_STARTED), and rolled-back failures. Only a root dispatch writes — a nested child
 * (an outer transaction already active on entry) defers to the root to avoid REQUIRES_NEW pressure.
 * The record is written in an independent transaction and the original exception is always
 * rethrown; a record failure is swallowed and logged, never substituted for the business exception.
 * When that write is lost, {@link OperationLogMetrics#failureRecordLost} fires as an alertable
 * audit-gap signal.
 */
public final class FailedOperationLogInterceptor implements CommandInterceptor {

  /** Outside concurrency translation (50), validation (100), and the transaction (200). */
  public static final int ORDER = 25;

  private static final Logger log = LoggerFactory.getLogger(FailedOperationLogInterceptor.class);

  private final OperationLogDefinitionRegistry registry;
  private final OperationLogInvocationFactory invocationFactory;
  private final OperationLogs operationLogs;
  private final FailureClassifier failureClassifier;
  private final FailureCompletionPolicy completionPolicy;
  private final TransactionState transactionState;
  private final IndependentTransactionRunner independentTransaction;
  private final OperationLogMetrics metrics;

  /** Builds an interceptor with no-op metrics. */
  public FailedOperationLogInterceptor(
      OperationLogDefinitionRegistry registry,
      OperationLogInvocationFactory invocationFactory,
      OperationLogs operationLogs,
      FailureClassifier failureClassifier,
      FailureCompletionPolicy completionPolicy,
      TransactionState transactionState,
      IndependentTransactionRunner independentTransaction) {
    this(
        registry,
        invocationFactory,
        operationLogs,
        failureClassifier,
        completionPolicy,
        transactionState,
        independentTransaction,
        OperationLogMetrics.noOp());
  }

  public FailedOperationLogInterceptor(
      OperationLogDefinitionRegistry registry,
      OperationLogInvocationFactory invocationFactory,
      OperationLogs operationLogs,
      FailureClassifier failureClassifier,
      FailureCompletionPolicy completionPolicy,
      TransactionState transactionState,
      IndependentTransactionRunner independentTransaction,
      OperationLogMetrics metrics) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.invocationFactory = Objects.requireNonNull(invocationFactory, "invocationFactory");
    this.operationLogs = Objects.requireNonNull(operationLogs, "operationLogs");
    this.failureClassifier = Objects.requireNonNull(failureClassifier, "failureClassifier");
    this.completionPolicy = Objects.requireNonNull(completionPolicy, "completionPolicy");
    this.transactionState = Objects.requireNonNull(transactionState, "transactionState");
    this.independentTransaction =
        Objects.requireNonNull(independentTransaction, "independentTransaction");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  @Override
  public <R> R intercept(Command<R> command, CommandContext context, Invocation<R> invocation) {
    Optional<OperationLogDefinition<Object, Object>> definition = registry.find(command.getClass());
    boolean nested = transactionState.hasActiveTransaction();
    try {
      return invocation.proceed();
    } catch (RuntimeException failure) {
      if (!nested && definition.isPresent()) {
        recordQuietly(command, context, definition.get(), failure);
      }
      throw failure;
    }
  }

  private void recordQuietly(
      Command<?> command,
      CommandContext context,
      OperationLogDefinition<Object, Object> definition,
      RuntimeException failure) {
    Optional<OperationLogDraft> draft;
    try {
      OperationLogInvocation logInvocation = invocationFactory.create(context);
      ClassifiedOutcome classified = failureClassifier.classify(failure, logInvocation);
      Completion completion = completionPolicy.decide(failure);
      long renderStart = System.nanoTime();
      draft =
          definition
              .failed(command, logInvocation, classified.failure())
              .map(d -> d.withResult(OperationResult.of(classified.outcome(), completion)));
      long renderNanos = System.nanoTime() - renderStart;
      draft.ifPresent(d -> metrics.renderLatencyNanos(d.operationCode(), renderNanos));
    } catch (RuntimeException prepareError) {
      // Classification/render failed: the failure record is lost, but the business exception must
      // still propagate untouched.
      metrics.failureRecordLost(command.getClass().getName());
      log.warn(
          "failed to prepare operation-log failure record for {}",
          command.getClass().getName(),
          prepareError);
      return;
    }
    draft.ifPresent(d -> writeQuietly(command, d));
  }

  private void writeQuietly(Command<?> command, OperationLogDraft draft) {
    try {
      independentTransaction.run(() -> operationLogs.record(draft));
    } catch (RuntimeException recordError) {
      // Never let a record failure replace the original business exception.
      metrics.failureRecordLost(draft.operationCode());
      log.warn(
          "failed to record operation-log failure for {}",
          command.getClass().getName(),
          recordError);
    }
  }

  @Override
  public int order() {
    return ORDER;
  }
}
