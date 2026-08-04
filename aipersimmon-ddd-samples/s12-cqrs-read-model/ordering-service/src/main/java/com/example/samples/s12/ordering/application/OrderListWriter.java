package com.example.samples.s12.ordering.application;

/**
 * The only way a projection row is ever written.
 *
 * <p>One method, and it takes a whole row. There is deliberately no {@code markPaid}, no {@code
 * updateSummary} and no {@code touch}: a projection maintained by partial updates is one where the row's
 * contents depend on the order the deltas arrived in, which is unrepairable — you cannot tell a drifted row
 * from a correct one, and a rebuild is a second implementation that has to agree with the first.
 *
 * <p>With whole-row upserts, every event and every rebuild go through {@link OrderListProjection#rebuild}
 * and produce the same bytes. The cost is reading the order again per event; the benefit is that "delete
 * the table and rebuild it" is a supported operation rather than an outage.
 */
public interface OrderListWriter {

  void save(OrderListItem item);

  /** Throw the whole projection away. Only a rebuild does this. */
  void deleteAll();
}
