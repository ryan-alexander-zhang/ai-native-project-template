package com.aipersimmon.ddd.operationlog.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OperationLogModelTest {

  @Test
  void actor_factories_set_type() {
    assertEquals("USER", Actor.user("u1", "Alice").type());
    assertEquals("SYSTEM", Actor.system("sys").type());
    assertEquals("SERVICE", Actor.service("cli").type());
    assertEquals("sys", Actor.system("sys").displayName());
  }

  @Test
  void target_of_has_no_display_name() {
    Target t = Target.of("Order", "o1");
    assertNull(t.displayName());
    assertThrows(NullPointerException.class, () -> Target.of("Order", null));
  }

  @Test
  void operation_result_rejects_nulls() {
    assertThrows(NullPointerException.class, () -> OperationResult.of(null, Completion.COMMITTED));
    assertThrows(NullPointerException.class, () -> OperationResult.of(Outcome.SUCCEEDED, null));
  }

  @Test
  void entry_times_and_causality_helpers() {
    assertThrows(NullPointerException.class, () -> new EntryTimes(null, Instant.EPOCH));
    Causality none = Causality.none();
    assertNull(none.messageId());
  }

  @Test
  void invocation_defaults_causality_and_requires_trusted_fields() {
    OperationLogInvocation inv =
        OperationLogInvocation.builder()
            .source("s")
            .actor(Actor.system("sys"))
            .occurredAt(Instant.EPOCH)
            .build();
    assertEquals("GLOBAL", inv.tenantId());
    assertNotNull(inv.causality());
    assertThrows(
        NullPointerException.class,
        () ->
            OperationLogInvocation.builder()
                .actor(Actor.system("s"))
                .occurredAt(Instant.EPOCH)
                .build());
  }

  @Test
  void entry_copies_lists_and_delegates_result_dimensions() {
    List<OperationChange> changes = new ArrayList<>();
    changes.add(new OperationChange("f", "F", "a", "b"));
    OperationLogEntry entry =
        new OperationLogEntry(
            "r1",
            "orders-service",
            "acme",
            "idem",
            "order.update",
            Actor.system("sys"),
            Target.of("Order", "o1"),
            OperationResult.of(Outcome.SUCCEEDED, Completion.COMMITTED),
            "summary",
            changes,
            List.of(),
            null,
            Causality.none(),
            new EntryTimes(Instant.EPOCH, Instant.EPOCH),
            null,
            1);

    changes.clear(); // mutating the source must not affect the frozen entry
    assertEquals(1, entry.changes().size());
    assertEquals(Outcome.SUCCEEDED, entry.outcome());
    assertEquals(Completion.COMMITTED, entry.completion());
    assertThrows(
        UnsupportedOperationException.class,
        () -> entry.changes().add(new OperationChange("x", "X", "1", "2")));
  }

  @Test
  void entry_requires_identity_fields() {
    assertThrows(
        NullPointerException.class,
        () ->
            new OperationLogEntry(
                null,
                "s",
                "t",
                "i",
                "c",
                Actor.system("sys"),
                Target.of("Order", "o1"),
                OperationResult.of(Outcome.SUCCEEDED, Completion.COMMITTED),
                null,
                List.of(),
                List.of(),
                null,
                Causality.none(),
                new EntryTimes(Instant.EPOCH, Instant.EPOCH),
                null,
                1));
  }
}
