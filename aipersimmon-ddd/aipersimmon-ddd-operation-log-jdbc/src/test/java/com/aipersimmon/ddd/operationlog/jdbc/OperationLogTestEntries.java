package com.aipersimmon.ddd.operationlog.jdbc;

import com.aipersimmon.ddd.operationlog.model.Actor;
import com.aipersimmon.ddd.operationlog.model.Causality;
import com.aipersimmon.ddd.operationlog.model.Completion;
import com.aipersimmon.ddd.operationlog.model.EntryTimes;
import com.aipersimmon.ddd.operationlog.model.OperationChange;
import com.aipersimmon.ddd.operationlog.model.OperationLogEntry;
import com.aipersimmon.ddd.operationlog.model.OperationResult;
import com.aipersimmon.ddd.operationlog.model.Outcome;
import com.aipersimmon.ddd.operationlog.model.Target;
import java.time.Instant;
import java.util.List;

/** Builds sample entries for the JDBC sink tests, shared so the column set is defined once. */
final class OperationLogTestEntries {

  private OperationLogTestEntries() {}

  static OperationLogEntry entry(String recordId, String idempotencyKey) {
    return entry(recordId, idempotencyKey, List.of());
  }

  static OperationLogEntry withChange(String recordId, String idempotencyKey) {
    return entry(
        recordId, idempotencyKey, List.of(new OperationChange("status", "状态", "OPEN", "CLOSED")));
  }

  static OperationLogEntry entry(
      String recordId, String idempotencyKey, List<OperationChange> changes) {
    return new OperationLogEntry(
        recordId,
        "orders",
        "acme",
        idempotencyKey,
        "order.update",
        Actor.system("sys"),
        Target.of("Order", "o1"),
        OperationResult.of(Outcome.SUCCEEDED, Completion.COMMITTED),
        "summary",
        changes,
        List.of(),
        null,
        Causality.none(),
        new EntryTimes(
            Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2020-01-01T00:00:05Z")),
        null,
        1);
  }
}
