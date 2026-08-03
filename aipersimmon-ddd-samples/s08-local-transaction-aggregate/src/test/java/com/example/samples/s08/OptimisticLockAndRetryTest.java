package com.example.samples.s08;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.application.DuplicateEntityException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.tenancy.Tenants;
import com.example.samples.s08.inventory.domain.Sku;
import com.example.samples.s08.inventory.domain.Stock;
import com.example.samples.s08.inventory.domain.Stocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** What the version predicate catches, what the retry replays, and what it must never replay. */
class OptimisticLockAndRetryTest extends InventoryTestBase {

  @Autowired private Stocks stocks;
  @Autowired private TransactionTemplate tx;
  @Autowired private FlakyOnce.ConflictHandler conflictHandler;
  @Autowired private FlakyOnce.DuplicateHandler duplicateHandler;

  @BeforeEach
  void resetHandlers() {
    conflictHandler.reset();
    duplicateHandler.reset();
  }

  @Test
  void awriteThatLostTheRaceAffectsNoRow() {
    Sku sku = new Sku("SKU-A");
    Stock loaded = tx.execute(status -> stocks.findBySku(sku).orElseThrow());

    // Somebody else commits in between.
    jdbc.update("UPDATE s08_stock SET available = 50, version = 2 WHERE sku = 'SKU-A'");

    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      loaded.reserve(10);
                      stocks.save(loaded);
                    }))
        .hasMessageContaining("was modified concurrently");

    // The lost update really was lost, not silently applied: 50, not 90.
    assertThat(availableOf("SKU-A")).isEqualTo(50);
  }

  @Test
  void aconflictIsTranslatedAndReplayedByTheRetryInterceptor() {
    // The handler throws ConcurrencyConflictException on its first attempt only. With retry enabled,
    // the dispatch still succeeds — and the answer says it took two attempts.
    Integer attempts = commandBus.send(new FlakyOnce.FailsWithConflictOnce());

    assertThat(attempts).isEqualTo(2);
  }

  @Test
  void acreateThatCollidedIsNotReplayed() {
    // DuplicateEntityException is a different type on purpose: replaying a create that already
    // happened would either collide forever or, worse, create a second one. The retry loop catches
    // only ConcurrencyConflictException, so this surfaces after exactly one attempt.
    assertThatThrownBy(() -> commandBus.send(new FlakyOnce.FailsWithDuplicateOnce()))
        .isInstanceOf(DuplicateEntityException.class);

    assertThat(duplicateHandler.attempts()).isEqualTo(1);
  }

  @Test
  void aretryIsAFreshDispatchNotAReplayOfTheSameAttempt() {
    // Each attempt gets its own transaction and reloads the aggregate, which is why retrying is sound
    // for a root dispatch at all: the second attempt decides against committed state, not against the
    // state the first attempt had read.
    CommandContext explicit = CommandContext.root(Tenants.ROOT, "message-1");
    Integer attempts = commandBus.send(new FlakyOnce.FailsWithConflictOnce(), explicit);

    assertThat(attempts).isEqualTo(2);
  }

}
