package com.aipersimmon.ddd.operationlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.operationlog.exception.OperationLogException;
import com.aipersimmon.ddd.operationlog.model.Actor;
import com.aipersimmon.ddd.operationlog.model.Causality;
import com.aipersimmon.ddd.operationlog.model.Completion;
import com.aipersimmon.ddd.operationlog.model.EntryTimes;
import com.aipersimmon.ddd.operationlog.model.OperationLogDraft;
import com.aipersimmon.ddd.operationlog.model.OperationLogEntry;
import com.aipersimmon.ddd.operationlog.model.OperationLogInvocation;
import com.aipersimmon.ddd.operationlog.model.OperationResult;
import com.aipersimmon.ddd.operationlog.model.Outcome;
import com.aipersimmon.ddd.operationlog.model.Target;
import com.aipersimmon.ddd.operationlog.port.AppendResult;
import com.aipersimmon.ddd.operationlog.port.OperationLogCriteria;
import com.aipersimmon.ddd.operationlog.port.OperationLogCursor;
import com.aipersimmon.ddd.operationlog.port.OperationLogPage;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Exercises the shipped public value types and the null-collection branches the write-path tests do
 * not reach, closing the framework-free core to the domain gate (JaCoCo 90/90/90 + PIT 90).
 */
class CoverageClosureTest {

  private static OperationLogInvocation invocationWithCausality() {
    return OperationLogInvocation.builder()
        .source("orders-service")
        .tenant("acme")
        .actor(Actor.user("u1", "Alice"))
        .causality(new Causality("m1", "c1", "cause1"))
        .occurredAt(Instant.EPOCH)
        .build();
  }

  @Test
  void exception_carries_message_and_cause() {
    OperationLogException withMessage = new OperationLogException("bad template");
    assertEquals("bad template", withMessage.getMessage());
    assertNull(withMessage.getCause());

    IllegalStateException cause = new IllegalStateException("root");
    OperationLogException withCause = new OperationLogException("append failed", cause);
    assertEquals("append failed", withCause.getMessage());
    assertSame(cause, withCause.getCause());
  }

  @Test
  void criteria_for_target_exposes_all_bounds() {
    OperationLogCriteria criteria =
        OperationLogCriteria.forTarget("acme", "Order", "o1", Instant.EPOCH, Instant.MAX, 50);
    assertEquals("acme", criteria.tenantId());
    assertEquals("Order", criteria.targetType());
    assertEquals("o1", criteria.targetId());
    assertEquals(Instant.EPOCH, criteria.from());
    assertEquals(Instant.MAX, criteria.to());
    assertEquals(50, criteria.pageSize());
  }

  @Test
  void cursor_start_has_no_token_and_of_carries_one() {
    assertNull(OperationLogCursor.start().token());
    assertEquals("t-9", OperationLogCursor.of("t-9").token());
  }

  @Test
  void page_freezes_supplied_items_and_keeps_next_cursor() {
    OperationLogPage page = new OperationLogPage(List.of(entryWithNullCollections()), "next-token");
    assertEquals(1, page.items().size());
    assertEquals("next-token", page.nextCursor());
    assertThrows(
        UnsupportedOperationException.class, () -> page.items().add(entryWithNullCollections()));
  }

  @Test
  void append_result_variants_carry_ids() {
    assertEquals("r1", new AppendResult.Appended("r1").recordId());
    assertEquals("r2", new AppendResult.Duplicate("r2").existingRecordId());
  }

  @Test
  void invocation_keeps_supplied_causality() {
    OperationLogInvocation invocation = invocationWithCausality();
    assertEquals("m1", invocation.causality().messageId());
    assertEquals("acme", invocation.tenantId());
  }

  @Test
  void entry_null_collections_become_empty() {
    OperationLogEntry entry = entryWithNullCollections();
    assertTrue(entry.changes().isEmpty());
    assertTrue(entry.details().isEmpty());
  }

  @Test
  void draft_direct_null_collections_become_empty() {
    OperationLogDraft draft =
        new OperationLogDraft(
            invocationWithCausality(),
            "order.update",
            Target.of("Order", "o1"),
            OperationResult.of(Outcome.SUCCEEDED, Completion.COMMITTED),
            "summary",
            null,
            null,
            null,
            null,
            null);
    assertTrue(draft.changes().isEmpty());
    assertTrue(draft.details().isEmpty());
  }

  @Test
  void draft_with_result_replaces_only_the_result_kind() {
    OperationLogDraft draft =
        OperationLogDraft.from(invocationWithCausality())
            .operation("order.update")
            .target("Order", "o1", null)
            .succeeded()
            .change("status", "状态", "OPEN", "CLOSED")
            .build();

    OperationLogDraft restated =
        draft.withResult(OperationResult.of(Outcome.FAILED, Completion.ROLLED_BACK));

    assertEquals(Outcome.FAILED, restated.result().outcome());
    assertEquals(Completion.ROLLED_BACK, restated.result().completion());
    assertEquals(draft.operationCode(), restated.operationCode());
    assertEquals(draft.changes(), restated.changes());
  }

  private static OperationLogEntry entryWithNullCollections() {
    return new OperationLogEntry(
        "r1",
        "orders-service",
        "acme",
        "idem",
        "order.update",
        Actor.system("sys"),
        Target.of("Order", "o1"),
        OperationResult.of(Outcome.SUCCEEDED, Completion.COMMITTED),
        "summary",
        null,
        null,
        null,
        Causality.none(),
        new EntryTimes(Instant.EPOCH, Instant.EPOCH),
        null,
        1);
  }
}
