package com.example.samples.s07.payments.domain;

/**
 * What happened when a gateway notification was applied. Returned rather than thrown, because none of
 * these four is an error: three of them are the normal consequences of an at-least-once, unordered
 * channel, and the fourth is a fact about the gateway that has to be recorded rather than resisted.
 *
 * <p>The caller needs the distinction because the HTTP answer to the gateway depends on it — and, more
 * importantly, because the answer is <strong>2xx in all four cases</strong>. A gateway redelivers until
 * it gets a success; answering 4xx to a duplicate it was right to send just means being told again.
 */
public enum SettlementOutcome {

  /** The payment moved forward. */
  APPLIED,

  /** Already further along: an older notification, arriving late. Nothing to do. */
  SUPERSEDED,

  /** The same terminal answer we already recorded. The at-least-once tax, paid quietly. */
  DUPLICATE,

  /**
   * A second, different terminal answer. The first is kept and the payment is flagged for a human,
   * because there is no rule that picks correctly between "charged" and "refused" — and choosing
   * silently would either give away goods or refuse a paying customer.
   */
  CONTRADICTED
}
