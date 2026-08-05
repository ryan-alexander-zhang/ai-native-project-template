package com.example.samples.s25.refunds.domain;

import java.util.Optional;

/** The refund repository, over the legacy table. */
public interface Refunds {

  Optional<Refund> find(RefundId id);

  void save(Refund refund);

  /** Whether this order already has an open refund — the tally the aggregate is handed. */
  boolean hasOpenRefund(long orderId);
}
