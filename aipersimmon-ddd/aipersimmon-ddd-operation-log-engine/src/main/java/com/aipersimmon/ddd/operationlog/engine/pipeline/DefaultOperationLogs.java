package com.aipersimmon.ddd.operationlog.engine.pipeline;

import com.aipersimmon.ddd.operationlog.engine.observability.AppendTags;
import com.aipersimmon.ddd.operationlog.engine.observability.OperationLogMetrics;
import com.aipersimmon.ddd.operationlog.model.EntryTimes;
import com.aipersimmon.ddd.operationlog.model.OperationLogDraft;
import com.aipersimmon.ddd.operationlog.model.OperationLogEntry;
import com.aipersimmon.ddd.operationlog.model.OperationLogInvocation;
import com.aipersimmon.ddd.operationlog.port.AppendResult;
import com.aipersimmon.ddd.operationlog.port.OperationLogSink;
import com.aipersimmon.ddd.operationlog.port.OperationLogs;
import com.aipersimmon.ddd.operationlog.port.RecordResult;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The default {@code OperationLogs} pipeline. It stamps a time-ordered {@code recordId} and {@code
 * recordedAt}, resolves the idempotency key, redacts and size-bounds the free text, freezes the
 * draft into an {@link OperationLogEntry}, and appends it via the injected {@link
 * OperationLogSink}, mapping the sink's result to a {@link RecordResult}. It emits the append-side
 * metrics (attempt/success/duplicate/failure counters, redact and append latencies) through the
 * injected {@link OperationLogMetrics}. Storage- and framework-free.
 */
public final class DefaultOperationLogs implements OperationLogs {

  private static final int SCHEMA_VERSION = 1;

  private final OperationLogSink sink;
  private final Clock clock;
  private final Supplier<String> idSupplier;
  private final Redactor redactor;
  private final OperationLogMetrics metrics;
  private final String sinkType;

  /**
   * Builds a pipeline with no-op metrics.
   *
   * @param sink the outbound write port
   * @param clock source of {@code recordedAt}
   * @param idSupplier source of {@code recordId} (prefer a time-ordered id such as ULID/UUIDv7)
   * @param limits size budgets applied before freezing
   */
  public DefaultOperationLogs(
      OperationLogSink sink, Clock clock, Supplier<String> idSupplier, OperationLogLimits limits) {
    this(sink, clock, idSupplier, limits, OperationLogMetrics.noOp());
  }

  /**
   * @param sink the outbound write port
   * @param clock source of {@code recordedAt}
   * @param idSupplier source of {@code recordId} (prefer a time-ordered id such as ULID/UUIDv7)
   * @param limits size budgets applied before freezing
   * @param metrics the metrics seam; use {@link OperationLogMetrics#noOp()} to disable
   */
  public DefaultOperationLogs(
      OperationLogSink sink,
      Clock clock,
      Supplier<String> idSupplier,
      OperationLogLimits limits,
      OperationLogMetrics metrics) {
    this.sink = Objects.requireNonNull(sink, "sink");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.idSupplier = Objects.requireNonNull(idSupplier, "idSupplier");
    this.redactor = new Redactor(Objects.requireNonNull(limits, "limits"));
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.sinkType = sink.getClass().getSimpleName();
  }

  @Override
  public RecordResult record(OperationLogDraft draft) {
    OperationLogInvocation invocation = draft.invocation();
    AppendTags tags =
        new AppendTags(draft.operationCode(), draft.result().outcome().name(), sinkType);
    metrics.appendAttempted(tags);
    long redactStart = System.nanoTime();
    String recordId = idSupplier.get();
    String idempotencyKey = resolveKey(draft, recordId);
    OperationLogEntry entry =
        new OperationLogEntry(
            recordId,
            invocation.source(),
            invocation.tenantId(),
            idempotencyKey,
            draft.operationCode(),
            invocation.actor(),
            draft.target(),
            draft.result(),
            redactor.summary(draft.summary()),
            redactor.changes(draft.changes()),
            redactor.details(draft.details()),
            draft.failure(),
            invocation.causality(),
            new EntryTimes(invocation.occurredAt(), clock.instant()),
            draft.templateRef(),
            SCHEMA_VERSION);
    metrics.redactLatencyNanos(System.nanoTime() - redactStart);
    AppendResult result = append(entry, tags);
    return switch (result) {
      case AppendResult.Appended a -> {
        metrics.appendSucceeded(tags);
        yield new RecordResult.Appended(a.recordId());
      }
      case AppendResult.Duplicate d -> {
        metrics.appendDuplicate(tags);
        yield new RecordResult.Duplicate(d.existingRecordId());
      }
    };
  }

  private AppendResult append(OperationLogEntry entry, AppendTags tags) {
    long appendStart = System.nanoTime();
    try {
      return sink.append(entry);
    } catch (RuntimeException failure) {
      metrics.appendFailed(tags);
      throw failure;
    } finally {
      metrics.appendLatencyNanos(sinkType, System.nanoTime() - appendStart);
    }
  }

  private static String resolveKey(OperationLogDraft draft, String recordId) {
    if (draft.idempotencyKey() != null) {
      return draft.idempotencyKey();
    }
    String messageId = draft.invocation().causality().messageId();
    if (messageId != null) {
      return IdempotencyKeys.derive(messageId, draft.operationCode(), draft.result());
    }
    return recordId;
  }
}
