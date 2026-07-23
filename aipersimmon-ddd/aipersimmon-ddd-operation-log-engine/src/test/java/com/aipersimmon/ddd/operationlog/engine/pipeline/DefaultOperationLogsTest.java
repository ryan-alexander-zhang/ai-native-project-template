package com.aipersimmon.ddd.operationlog.engine.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.operationlog.model.Actor;
import com.aipersimmon.ddd.operationlog.model.Causality;
import com.aipersimmon.ddd.operationlog.model.OperationLogDraft;
import com.aipersimmon.ddd.operationlog.model.OperationLogEntry;
import com.aipersimmon.ddd.operationlog.model.OperationLogInvocation;
import com.aipersimmon.ddd.operationlog.port.AppendResult;
import com.aipersimmon.ddd.operationlog.port.OperationLogSink;
import com.aipersimmon.ddd.operationlog.port.RecordResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DefaultOperationLogsTest {

  private static final Instant OCCURRED = Instant.parse("2020-01-01T00:00:00Z");
  private static final Instant RECORDED = Instant.parse("2020-01-02T00:00:00Z");

  private static final class CapturingSink implements OperationLogSink {
    private OperationLogEntry captured;
    private AppendResult result = new AppendResult.Appended("rec-1");

    @Override
    public AppendResult append(OperationLogEntry entry) {
      this.captured = entry;
      return result;
    }
  }

  private static OperationLogInvocation invocation(Causality causality) {
    return OperationLogInvocation.builder()
        .source("orders-service")
        .tenant("acme")
        .actor(Actor.user("u1", "Alice"))
        .causality(causality)
        .occurredAt(OCCURRED)
        .build();
  }

  private static DefaultOperationLogs logs(CapturingSink sink, OperationLogLimits limits) {
    return new DefaultOperationLogs(
        sink, Clock.fixed(RECORDED, ZoneOffset.UTC), () -> "rec-1", limits);
  }

  @Test
  void stamps_ids_times_and_maps_appended() {
    CapturingSink sink = new CapturingSink();
    RecordResult result =
        logs(sink, OperationLogLimits.defaults())
            .record(
                OperationLogDraft.from(invocation(Causality.none()))
                    .operation("order.remark.update")
                    .target("Order", "o1", "SO-1")
                    .succeeded()
                    .summary("ok")
                    .build());

    OperationLogEntry e = sink.captured;
    assertEquals("rec-1", e.recordId());
    assertEquals(OCCURRED, e.times().occurredAt());
    assertEquals(RECORDED, e.times().recordedAt());
    assertEquals(1, e.schemaVersion());
    assertEquals("acme", e.tenantId());
    assertEquals(new RecordResult.Appended("rec-1"), result);
  }

  @Test
  void explicit_idempotency_key_is_used_verbatim() {
    CapturingSink sink = new CapturingSink();
    logs(sink, OperationLogLimits.defaults())
        .record(
            OperationLogDraft.from(invocation(Causality.none()))
                .operation("order.close.stale")
                .target("Order", "o1", null)
                .succeeded()
                .idempotencyKey("close-stale:o1:2020-01-01")
                .build());
    assertEquals("close-stale:o1:2020-01-01", sink.captured.idempotencyKey());
  }

  @Test
  void derives_sha256_key_from_message_id() {
    CapturingSink sink = new CapturingSink();
    logs(sink, OperationLogLimits.defaults())
        .record(
            OperationLogDraft.from(invocation(new Causality("m1", "c1", null)))
                .operation("order.cancel")
                .target("Order", "o1", null)
                .succeeded()
                .build());
    String key = sink.captured.idempotencyKey();
    assertEquals(64, key.length());
    assertTrue(key.matches("[0-9a-f]{64}"));
  }

  @Test
  void falls_back_to_record_id_when_no_key_and_no_message_id() {
    CapturingSink sink = new CapturingSink();
    logs(sink, OperationLogLimits.defaults())
        .record(
            OperationLogDraft.from(invocation(Causality.none()))
                .operation("x")
                .target("Order", "o1", null)
                .succeeded()
                .build());
    assertEquals("rec-1", sink.captured.idempotencyKey());
  }

  @Test
  void maps_duplicate_result() {
    CapturingSink sink = new CapturingSink();
    sink.result = new AppendResult.Duplicate("existing-9");
    RecordResult result =
        logs(sink, OperationLogLimits.defaults())
            .record(
                OperationLogDraft.from(invocation(Causality.none()))
                    .operation("x")
                    .target("Order", "o1", null)
                    .succeeded()
                    .build());
    assertEquals(new RecordResult.Duplicate("existing-9"), result);
    assertInstanceOf(RecordResult.Duplicate.class, result);
  }

  @Test
  void strips_newlines_truncates_summary_and_caps_changes() {
    CapturingSink sink = new CapturingSink();
    logs(sink, new OperationLogLimits(5, 1, 20, 512))
        .record(
            OperationLogDraft.from(invocation(Causality.none()))
                .operation("x")
                .target("Order", "o1", null)
                .succeeded()
                .summary("ab\ncd\nefgh")
                .change("f1", "L1", "a", "b")
                .change("f2", "L2", "c", "d")
                .build());
    assertEquals("ab cd", sink.captured.summary());
    assertEquals(1, sink.captured.changes().size());
    assertEquals("f1", sink.captured.changes().get(0).field());
  }
}
