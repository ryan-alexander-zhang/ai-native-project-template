package com.example.samples.s27.customer.application;

import com.example.samples.s27.customer.domain.CustomerId;

/**
 * Consent rows, which exist to make deduplication observable.
 *
 * <p>An inbox stops a message from being processed twice, and "was it processed twice" is unanswerable unless
 * processing leaves a mark. This is the mark: one row per absorbed signal, counted. It is also, incidentally,
 * personal data that erasure has to deal with — a consent record is about a person — so it is a second place
 * the erasure has to reach, and the sample says so rather than pretending the aggregate is the only home for
 * personal data.
 */
public interface MarketingConsents {

  /** Record a consent. Append-only, so a duplicate absorption shows up as a second row. */
  void grant(CustomerId customerId, String note);

  long countFor(CustomerId customerId);

  /**
   * Remove every consent for a customer. Called by the erasure, because a consent row names a person.
   *
   * @return how many were removed
   */
  int forget(CustomerId customerId);
}
