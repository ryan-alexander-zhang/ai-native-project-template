package com.aipersimmon.ddd.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The two small doubles: pass-through work, pair-keyed dedup. */
class ImmediateUnitOfWorkAndInboxTest {

  @Test
  void theUnitOfWorkRunsTheWorkAndCountsTheBoundary() {
    ImmediateUnitOfWork unitOfWork = new ImmediateUnitOfWork();

    assertEquals("done", unitOfWork.execute(() -> "done"));
    unitOfWork.execute(() -> {});

    assertEquals(2, unitOfWork.executions());
  }

  @Test
  void theUnitOfWorkLetsAFailureOut() {
    ImmediateUnitOfWork unitOfWork = new ImmediateUnitOfWork();

    assertThrows(
        IllegalStateException.class,
        () ->
            unitOfWork.execute(
                () -> {
                  throw new IllegalStateException("boom");
                }));
  }

  @Test
  void theInboxDeduplicatesOnTheSourceAndKeyPair() {
    InMemoryInbox inbox = new InMemoryInbox();

    assertFalse(inbox.alreadyProcessed("/ordering", "evt-1"), "first delivery proceeds");
    assertTrue(inbox.alreadyProcessed("/ordering", "evt-1"), "redelivery is recognised");
    // The contract's subtle half: identity is the pair, so another producer's evt-1 is a
    // different event, not a redelivery.
    assertFalse(inbox.alreadyProcessed("/payment", "evt-1"));
    assertEquals(2, inbox.size());
  }
}
