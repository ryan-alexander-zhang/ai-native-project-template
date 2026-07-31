package com.example.ordering.domain.customer;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.annotation.Identity;
import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import com.example.ordering.domain.shared.Money;

/**
 * The customer's <strong>credit</strong>: how much they may owe across open orders, and how much of
 * that is currently committed. The invariant is one line — {@code used + amount <= limit} — and
 * {@link #reserveCredit} is the only way past it.
 *
 * <h2>Why this is an aggregate, and why it is only this much of one</h2>
 *
 * <p>It used to hold a limit and nothing else: every field final, its only behaviour a query, no
 * {@code save} on the repository port, no version column, no way to create one outside a Flyway
 * seed. That is a read-only projection wearing an aggregate's annotations, and it taught the wrong
 * lesson about what the word means (issue-00086). It is a real aggregate now because it has
 * something real to protect — a mutable balance with a rule over it that two concurrent placements
 * can race for.
 *
 * <p>Deliberately it protects <em>only</em> that. A customer's name, contact details and lifecycle
 * belong to a customer/CRM context, not to ordering; promoting all of it to a local aggregate would
 * entrench exactly the context-mapping mistake issue-00086 identified. {@code name} survives as a
 * label carried alongside the row, not as state this context governs — nothing mutates it and
 * nothing should. What ordering genuinely owns is the answer to "how much more may this customer
 * order?", because ordering is what spends it and gives it back.
 *
 * <h2>Reserve on placement, release on cancellation</h2>
 *
 * <p>Credit is committed when an order is placed and returned when it is cancelled; a confirmed
 * order keeps its credit, because by then the customer really does owe it. Every path that cancels
 * has to release, or credit leaks and a customer is slowly locked out by orders that no longer
 * exist.
 */
@AggregateRoot
public class Customer extends AbstractAggregateRoot<CustomerId> {

  private final CustomerId id;
  private final String name;
  private final Money creditLimit;
  private Money usedCredit;

  public Customer(CustomerId id, String name, Money creditLimit) {
    // The delegation derives the zero balance from the limit's currency, so the limit must be
    // refused here — before it is dereferenced — for the refusal to be the domain's own.
    this(id, name, requiredLimit(creditLimit), Money.of(0, creditLimit.currency()));
  }

  private static Money requiredLimit(Money creditLimit) {
    if (creditLimit == null) {
      throw new DomainException("a customer needs a credit limit");
    }
    return creditLimit;
  }

  /**
   * The one gate both creation and rehydration pass through. A customer with no id or no limit is
   * corrupt however it arrives, and a used balance in another currency would make every {@code
   * reserveCredit} comparison meaningless — a bad row rehydrated without complaint here explodes
   * later, in {@code reserveCredit}, far from the row that caused it.
   *
   * <p>Deliberately <em>not</em> guarded: {@code used <= limit}. A lowered credit limit legally
   * strands existing debt above the new limit; such a customer can release and cannot reserve,
   * which is exactly right, so rejecting the row would refuse to load legitimate history.
   */
  private Customer(CustomerId id, String name, Money creditLimit, Money usedCredit) {
    if (id == null) {
      throw new DomainException("a customer needs its identity");
    }
    if (creditLimit == null) {
      throw new DomainException("a customer needs a credit limit");
    }
    if (usedCredit == null) {
      throw new DomainException("a customer needs its used balance, even when it is zero");
    }
    if (!usedCredit.currency().equals(creditLimit.currency())) {
      throw new DomainException(
          "used credit is in "
              + usedCredit.currency()
              + " but the limit is in "
              + creditLimit.currency()
              + " — the two must share a currency for the limit to mean anything");
    }
    this.id = id;
    this.name = name;
    this.creditLimit = creditLimit;
    this.usedCredit = usedCredit;
  }

  /**
   * Reconstitute a stored customer row. For persistence adapters only.
   *
   * @param version the row's optimistic-lock version, which the repository puts back in the {@code
   *     WHERE} clause when it saves. This is what stops two concurrent placements from each
   *     reserving against the same snapshot of {@code usedCredit} and overshooting the limit
   *     between them — the concurrency hole issue-00071 described, which existed precisely because
   *     nothing was ever written here.
   */
  public static Customer reconstitute(
      CustomerId id, String name, Money creditLimit, Money usedCredit, long version) {
    Customer customer = new Customer(id, name, creditLimit, usedCredit);
    customer.restoreVersion(version);
    return customer;
  }

  /**
   * Commit {@code amount} against the limit, or refuse.
   *
   * <p>The predicate is {@code used + amount <= limit}, and that is the whole difference between a
   * credit limit and a per-order cap. The check this replaces compared each order against the full
   * limit in isolation, so two orders of 60,000 both passed a limit of 100,000 with no concurrency
   * involved at all — a rule that never consults what has already been spent is not a credit limit,
   * whatever the error code is called (issue-00071).
   */
  public void reserveCredit(Money amount) {
    if (amount == null) {
      throw new DomainException("an amount is required to reserve credit");
    }
    Money wouldBeUsed = usedCredit.plus(amount);
    if (!wouldBeUsed.lessThanOrEqual(creditLimit)) {
      throw new CreditExceededException(
          "customer "
              + id.value()
              + " has "
              + availableCredit().amountMinor()
              + " of "
              + creditLimit.amountMinor()
              + " "
              + creditLimit.currency()
              + " left and cannot commit a further "
              + amount.amountMinor());
    }
    this.usedCredit = wouldBeUsed;
  }

  /**
   * Return credit committed by an order that will not now be paid for. {@link Money#minus} refuses
   * to go negative, so releasing more than was reserved fails loudly rather than quietly handing
   * the customer extra headroom.
   */
  public void releaseCredit(Money amount) {
    this.usedCredit = usedCredit.minus(amount);
  }

  public Money creditLimit() {
    return creditLimit;
  }

  public Money usedCredit() {
    return usedCredit;
  }

  /** What is still available to commit — the limit less what open orders already hold. */
  public Money availableCredit() {
    return creditLimit.minus(usedCredit);
  }

  @Override
  @Identity
  public CustomerId id() {
    return id;
  }

  public String name() {
    return name;
  }
}
