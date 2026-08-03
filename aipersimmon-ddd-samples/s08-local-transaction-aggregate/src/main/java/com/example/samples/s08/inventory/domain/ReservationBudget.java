package com.example.samples.s08.inventory.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import com.aipersimmon.ddd.core.rule.Invariant;

/**
 * The owner of a rule that spans stock items: how many units may be reserved across all skus.
 *
 * <p>It exists because per-aggregate versions cannot protect such a rule — each command reads
 * different stock rows, so no version they check overlaps, and two commands can both pass the check
 * and both write. Giving the rule a row of its own makes the version predicate on <em>that</em> row the
 * serialisation point. {@code CrossAggregateRuleTest} shows both halves: the rule breaking without it,
 * and holding with it.
 */
@AggregateRoot
public final class ReservationBudget extends AbstractAggregateRoot<BudgetId> {

  private final BudgetId id;
  private final int limit;
  private int reserved;

  private ReservationBudget(BudgetId id, int limit, int reserved) {
    this.id = id;
    this.limit = limit;
    this.reserved = reserved;
  }

  public static ReservationBudget reconstitute(BudgetId id, int limit, int reserved, long version) {
    ReservationBudget budget = new ReservationBudget(id, limit, reserved);
    budget.restoreVersion(version);
    return budget;
  }

  public void debit(int units) {
    checkInvariant(new WithinBudget(limit, reserved, units));
    this.reserved += units;
  }

  @Override
  public BudgetId id() {
    return id;
  }

  public int reserved() {
    return reserved;
  }

  public int limit() {
    return limit;
  }

  private record WithinBudget(int limit, int reserved, int wanted) implements Invariant {

    @Override
    public boolean isBroken() {
      return reserved + wanted > limit;
    }

    @Override
    public String message() {
      return "reserving " + wanted + " would take the total to " + (reserved + wanted)
          + ", past the limit of " + limit;
    }

    @Override
    public ErrorCode errorCode() {
      return InventoryErrorCode.BUDGET_EXCEEDED;
    }
  }
}
