package com.example.samples.s12.ordering.application;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Rebuild the whole projection — the operation that decides whether a projection is an asset or a liability.
 *
 * <p>Six lines, and they are six lines only because of two earlier decisions. The projection recomputes whole
 * rows rather than applying deltas, so this reuses the same method the event handlers use. And the product
 * names live in <em>this</em> service's replica rather than being copied straight from the wire into the
 * projection row, so the inputs are all local.
 *
 * <p><strong>That second one is the non-obvious part.</strong> Had the rename listener written the new name
 * directly into {@code s12_order_list.display_summary} and kept no replica, this method could not exist: the
 * names would be recoverable only by asking the catalogue again or by replaying its whole event history from
 * the broker's retention — which is not a rebuild, it is a migration with an external dependency and a
 * deadline. A projection is rebuildable exactly when every input it needs is a table you own.
 *
 * <p>What this deliberately is <em>not</em>: online. It runs in one transaction, deletes everything and
 * writes it back, so the list is briefly empty to concurrent readers and the whole thing is one long lock. For
 * a sample's data volume that is honest and simple; at scale a rebuild writes into a second table and swaps a
 * name or a view, which is S23's territory rather than this one's.
 */
@Component
class RebuildOrderListHandler implements CommandHandler<RebuildOrderList, Integer> {

  private static final Logger log = LoggerFactory.getLogger(RebuildOrderListHandler.class);

  private final OrderFacts orderFacts;
  private final OrderListWriter writer;
  private final OrderListProjection projection;

  RebuildOrderListHandler(
      OrderFacts orderFacts, OrderListWriter writer, OrderListProjection projection) {
    this.orderFacts = orderFacts;
    this.writer = writer;
    this.projection = projection;
  }

  @Override
  public Integer handle(RebuildOrderList command, CommandContext context) {
    writer.deleteAll();
    List<String> orderIds = orderFacts.allOrderIds();
    orderIds.forEach(projection::rebuild);
    log.info("order list rebuilt from {} orders", orderIds.size());
    return orderIds.size();
  }
}
