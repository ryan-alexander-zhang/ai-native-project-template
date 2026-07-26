package com.aipersimmon.ddd.operationlog.cqrs.capture;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.operationlog.annotation.OperationLog;
import com.aipersimmon.ddd.operationlog.model.Actor;
import com.aipersimmon.ddd.operationlog.model.OperationLogDraft;
import com.aipersimmon.ddd.operationlog.port.OperationLogs;
import com.aipersimmon.ddd.operationlog.port.RecordResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared test commands, results, and fakes for the capture tests. Public (like real application
 * commands) so the template engine's strict, no-{@code setAccessible} property access can read
 * them.
 */
public final class CaptureTestFixtures {

  private CaptureTestFixtures() {}

  static OperationLogInvocationFactory invocationFactory() {
    return new OperationLogInvocationFactory(
        "orders-service",
        Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC),
        () -> Actor.system("sys"),
        () -> "acme");
  }

  /** Captures recorded drafts; optionally fails the append to test fail-closed / swallow paths. */
  static final class CapturingOperationLogs implements OperationLogs {
    final List<OperationLogDraft> recorded = new ArrayList<>();
    boolean failOnRecord;

    @Override
    public RecordResult record(OperationLogDraft draft) {
      recorded.add(draft);
      if (failOnRecord) {
        throw new IllegalStateException("sink boom");
      }
      return new RecordResult.Appended("r-" + recorded.size());
    }
  }

  @OperationLog(
      code = "order.remark.update",
      targetType = "Order",
      targetId = "${input.orderId}",
      success = "remark set to ${truncate(input.remark, 50)}",
      failure = "remark update failed",
      rejectedWhen = "${resultProjection.rejected}")
  public record UpdateRemark(String orderId, String remark) implements Command<RemarkResult> {}

  @OperationLog(
      code = "order.silent",
      targetType = "Order",
      targetId = "${input.orderId}",
      success = "ok",
      recordFailure = false)
  public record SilentOnFailure(String orderId) implements Command<Void> {}

  public record RemarkResult(boolean rejected) {}
}
