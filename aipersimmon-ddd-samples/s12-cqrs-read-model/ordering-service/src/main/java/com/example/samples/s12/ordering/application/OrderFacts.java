package com.example.samples.s12.ordering.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The write model, read for the purpose of projecting it.
 *
 * <p>A separate port from {@code Orders} on purpose. {@code Orders} returns aggregates, which is right for
 * commands and wrong here: the projection has no business calling {@code markPaid}, and handing it an
 * aggregate invites exactly that. This port returns a flat snapshot and nothing else — the read side asking
 * the write side a question, in a shape that cannot be mistaken for permission to change anything.
 */
public interface OrderFacts {

  Optional<OrderFact> find(String orderId);

  /** Every order containing this sku. The rename path's whole cost lives in this query. */
  List<String> orderIdsContaining(String sku);

  /** Every order, oldest first. Used only by a rebuild. */
  List<String> allOrderIds();

  /** A flat snapshot of one order. */
  record OrderFact(
      String orderId,
      String customerId,
      String status,
      Instant placedAt,
      Instant paidAt,
      long totalMinor,
      List<String> skusInOrder) {}
}
