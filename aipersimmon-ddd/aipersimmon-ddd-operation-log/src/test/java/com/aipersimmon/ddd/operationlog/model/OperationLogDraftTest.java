package com.aipersimmon.ddd.operationlog.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class OperationLogDraftTest {

  private static OperationLogInvocation invocation() {
    return OperationLogInvocation.builder()
        .source("orders-service")
        .tenant("acme")
        .actor(Actor.user("u1", "Alice"))
        .occurredAt(Instant.EPOCH)
        .build();
  }

  @Test
  void succeeded_defaults_completion_to_committed() {
    OperationLogDraft draft =
        OperationLogDraft.from(invocation())
            .operation("order.remark.update")
            .target("Order", "o1", "SO-1")
            .succeeded()
            .summary("changed")
            .build();

    assertEquals(Outcome.SUCCEEDED, draft.result().outcome());
    assertEquals(Completion.COMMITTED, draft.result().completion());
    assertNull(draft.failure());
  }

  @Test
  void succeeded_completion_can_be_overridden_to_unknown() {
    OperationLogDraft draft =
        OperationLogDraft.from(invocation())
            .operation("order.force-refund")
            .target(new Target("Order", "o1", null))
            .succeeded()
            .completion(Completion.UNKNOWN)
            .build();

    assertEquals(Completion.UNKNOWN, draft.result().completion());
  }

  @Test
  void failed_sets_failed_outcome_rolled_back_and_failure() {
    ClassifiedFailure failure = new ClassifiedFailure("order.closed", "ORDER_STATE", "closed");
    OperationLogDraft draft =
        OperationLogDraft.from(invocation())
            .operation("order.cancel")
            .target("Order", "o1", null)
            .failed(failure)
            .build();

    assertEquals(Outcome.FAILED, draft.result().outcome());
    assertEquals(Completion.ROLLED_BACK, draft.result().completion());
    assertEquals(failure, draft.failure());
  }

  @Test
  void rejected_not_started_records_changes_and_details() {
    OperationLogDraft draft =
        OperationLogDraft.from(invocation())
            .operation("order.cancel")
            .target("Order", "o1", null)
            .rejected()
            .completion(Completion.NOT_STARTED)
            .change("status", "状态", "OPEN", "CANCELLED")
            .detail("reason", "user")
            .idempotencyKey("k1")
            .templateRef("order.cancel", "1")
            .build();

    assertEquals(Outcome.REJECTED, draft.result().outcome());
    assertEquals(Completion.NOT_STARTED, draft.result().completion());
    assertEquals(1, draft.changes().size());
    assertEquals(1, draft.details().size());
    assertEquals("k1", draft.idempotencyKey());
    assertEquals(new TemplateRef("order.cancel", "1"), draft.templateRef());
  }

  @Test
  void draft_change_and_detail_lists_are_immutable() {
    OperationLogDraft draft =
        OperationLogDraft.from(invocation())
            .operation("x")
            .target("Order", "o1", null)
            .succeeded()
            .change("a", "A", "1", "2")
            .build();

    assertThrows(
        UnsupportedOperationException.class,
        () -> draft.changes().add(new OperationChange("b", "B", "1", "2")));
    assertThrows(
        UnsupportedOperationException.class,
        () -> draft.details().add(new OperationDetail("n", "v")));
  }

  @Test
  void build_without_outcome_throws() {
    OperationLogDraft.Builder builder =
        OperationLogDraft.from(invocation()).operation("x").target("Order", "o1", null);
    assertThrows(NullPointerException.class, builder::build);
  }

  @Test
  void from_null_invocation_throws() {
    assertThrows(NullPointerException.class, () -> OperationLogDraft.from(null));
  }
}
