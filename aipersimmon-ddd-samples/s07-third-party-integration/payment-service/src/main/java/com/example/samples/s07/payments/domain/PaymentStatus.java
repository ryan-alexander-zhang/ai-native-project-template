package com.example.samples.s07.payments.domain;

/**
 * Where a payment has got to, in <em>our</em> words, ordered by how much is settled.
 *
 * <p>The rank is the whole out-of-order defence. Webhook deliveries are not ordered — two callbacks
 * sent a millisecond apart can arrive in either order, and a redelivery of an old one can arrive after
 * a new one — so the aggregate cannot trust arrival order and must not trust the sender's timestamp
 * either (it is the sender's clock, and a retried delivery carries the original send time). What it can
 * trust is that these states only ever move forward: a payment that has succeeded cannot become merely
 * accepted again. Comparing ranks turns "which of these two notifications is newer" into a question
 * about the states themselves.
 *
 * <p>Note what is <em>not</em> here: a status for "we do not know". Not knowing is not a state of the
 * payment, it is a state of our information — modelled as {@code review_reason} on the aggregate, which
 * leaves the status free to be settled later by a callback that finally arrives.
 */
public enum PaymentStatus {

  /** The intent is recorded and durable. Nothing has been sent. */
  REQUESTED(0),

  /** The gateway has acknowledged the request. No money has moved. */
  SUBMITTED(1),

  /** The gateway charged the customer. */
  SUCCEEDED(2),

  /** The gateway refused. A business outcome, and as final as success. */
  FAILED(2);

  private final int rank;

  PaymentStatus(int rank) {
    this.rank = rank;
  }

  int rank() {
    return rank;
  }

  /** Whether this is an answer rather than a stage. */
  public boolean isTerminal() {
    return rank == 2;
  }

  /** Whether {@code other} says less about the payment than this state already does. */
  boolean supersedes(PaymentStatus other) {
    return rank > other.rank;
  }
}
