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
 * rejections (NOT_STARTED), and rolled-back failures. The record is written in an independent
 * transaction and the original exception is always rethrown; a record failure is swallowed and
 * logged, never substituted for the business exception. When that write is lost, {@link
 * OperationLogMetrics#failureRecordLost} fires as an alertable audit-gap signal.
 *
 * <p><strong>Every failing command with a definition records its own failure.</strong> This used to
 * skip the record whenever a transaction was already active on entry, on the reading that such a
 * dispatch must be a nested child which the root would record for. Two things were wrong with it. A
 * root dispatch invoked from inside a caller's transaction — a {@code @Transactional} service
 * method, a scheduled job, a listener — looks exactly the same, so there was no root left to record
 * and every failure in the flow was skipped in silence. And where a root did record, it recorded
 * the root's operation code, so the child operation that actually failed never appeared. Recording
 * per command costs a suspend-and-resume per level of nesting; an audit log that quietly omits
 * failures is not worth saving that.
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
  private final IndependentTransactionRunner independentTransaction;
  private final OperationLogMetrics metrics;

  /** Builds an interceptor with no-op metrics. */
  public FailedOperationLogInterceptor(
      OperationLogDefinitionRegistry registry,
      OperationLogInvocationFactory invocationFactory,
      OperationLogs operationLogs,
      FailureClassifier failureClassifier,
      FailureCompletionPolicy completionPolicy,
      IndependentTransactionRunner independentTransaction) {
    this(
        registry,
        invocationFactory,
        operationLogs,
        failureClassifier,
        completionPolicy,
        independentTransaction,
        OperationLogMetrics.noOp());
  }

  public FailedOperationLogInterceptor(
      OperationLogDefinitionRegistry registry,
      OperationLogInvocationFactory invocationFactory,
      OperationLogs operationLogs,
      FailureClassifier failureClassifier,
      FailureCompletionPolicy completionPolicy,
      IndependentTransactionRunner independentTransaction,
      OperationLogMetrics metrics) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.invocationFactory = Objects.requireNonNull(invocationFactory, "invocationFactory");
    this.operationLogs = Objects.requireNonNull(operationLogs, "operationLogs");
    this.failureClassifier = Objects.requireNonNull(failureClassifier, "failureClassifier");
    this.completionPolicy = Objects.requireNonNull(completionPolicy, "completionPolicy");
    this.independentTransaction =
        Objects.requireNonNull(independentTransaction, "independentTransaction");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  @Override
  public <R> R intercept(Command<R> command, CommandContext context, Invocation<R> invocation) {
    Optional<OperationLogDefinition<Object, Object>> definition = registry.find(command.getClass());
    try {
      return invocation.proceed();
    } catch (RuntimeException failure) {
      definition.ifPresent(d -> recordQuietly(command, context, d, failure));
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
