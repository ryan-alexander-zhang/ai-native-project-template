package com.example.ordering.domain.customer;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.annotation.Identity;
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
    this(id, name, creditLimit, Money.of(0, creditLimit.currency()));
  }

  private Customer(CustomerId id, String name, Money creditLimit, Money usedCredit) {
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
