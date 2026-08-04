package com.example.samples.s12.ordering.application;

import com.aipersimmon.ddd.application.DomainEventHandler;
import com.aipersimmon.ddd.cqrs.Projection;
import com.example.samples.s12.ordering.domain.OrderPaid;
import com.example.samples.s12.ordering.domain.OrderPlaced;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Maintains the order list. One public operation, two ways in, and no SQL.
 *
 * <p><strong>{@code @EventListener}, not {@code @TransactionalEventListener}</strong> — and what that choice
 * does and does not decide is worth being exact about, because the obvious answer is wrong here.
 *
 * <p>It is tempting to say the in-transaction phase is what buys read-your-own-writes. In this application it
 * is not. The command bus opens the transaction and nothing wraps it, so an {@code AFTER_COMMIT} listener
 * would still run synchronously, on the same thread, before {@code send} returned — the customer would see
 * their order in the list either way. Measured: switching both methods to {@code AFTER_COMMIT} leaves every
 * test in this service green. A claim that survives its own negative control is the only kind worth making.
 *
 * <p>What the phase actually decides is <strong>which way a failure propagates</strong>:
 *
 * <ul>
 *   <li>In the transaction (here): if this throws, the order is not placed. The read side is on the write
 *       side's critical path, so a projection bug is an outage on ordering — but there is never an order
 *       without its list row.
 *   <li>{@code AFTER_COMMIT}: the order commits and the list row is silently missing, with nothing holding a
 *       retry. The write is safe and the projection has drifted, detectably only by a rebuild.
 * </ul>
 *
 * <p>So the judgement is not about lag, it is about which failure you would rather have: <em>share the
 * transaction when a missing row would be worse than a failed write, and do not when the projection is
 * expensive, remote, or optional.</em> Here the projection is two local statements and the list is the screen
 * the customer lands on, so refusing the write is the better of the two. A projection that called out to
 * Elasticsearch would answer the opposite way — and would then need the drift detection that this one does
 * not.
 *
 * <p>Read-your-own-writes, meanwhile, is bought by something else entirely: the projection being maintained
 * <em>in process</em> at all. Move it behind a queue and no phase annotation saves it.
 *
 * <p>Note also what drives this class versus what drives the product name. The order's own facts arrive as
 * domain events, in process, synchronously — lag zero. The product name arrives as an integration event
 * over a broker — lag measurable, and measured. Same projection table, two clocks, and the difference is
 * not a design flaw but the reason ownership matters.
 */
@Component
@Projection
@DomainEventHandler
public class OrderListProjection {

  private final OrderFacts orderFacts;
  private final ProductNames productNames;
  private final OrderListWriter writer;
  private final Clock clock;

  OrderListProjection(
      OrderFacts orderFacts, ProductNames productNames, OrderListWriter writer, Clock clock) {
    this.orderFacts = orderFacts;
    this.productNames = productNames;
    this.writer = writer;
    this.clock = clock;
  }

  @EventListener
  void on(OrderPlaced event) {
    rebuild(event.orderId().value());
  }

  @EventListener
  void on(OrderPaid event) {
    rebuild(event.orderId().value());
  }

  /**
   * Recompute one row from scratch, whatever happened to it.
   *
   * <p>The same method serves a placement, a payment, a product rename and a full rebuild. That is the
   * design: there is exactly one definition of what a row contains, so no two paths can disagree about it.
   * Calling it twice produces the same row, so a redelivered event is harmless without any dedup of its own.
   *
   * <p>A missing order is silence rather than an error — a rename can name an order that has since been
   * deleted, and a rebuild races nothing but is defensive for free.
   */
  public void rebuild(String orderId) {
    orderFacts
        .find(orderId)
        .ifPresent(
            fact -> {
              // Stands in for a real failure inside the projection: a column that no longer fits, a null
              // nobody expected, a bug. A sample's affordance in the style S3 established, and the only way
              // to observe which side of the transaction boundary this class is on.
              if (fact.customerId().startsWith("poison")) {
                throw new IllegalStateException(
                    "the projection cannot build a row for " + fact.customerId());
              }
              writer.save(project(fact));
            });
  }

  private OrderListItem project(OrderFacts.OrderFact fact) {
    List<String> distinctSkus = new java.util.ArrayList<>(new LinkedHashSet<>(fact.skusInOrder()));
    Map<String, String> known = productNames.namesOf(distinctSkus);
    // A sku this context has not been told about yet shows as its sku. Not a blank, not a "?", not an
    // exception: the customer sees something stable and recognisable, and it corrects itself the moment the
    // catalogue's event arrives. The alternative — refusing to project until every name is known — makes an
    // unrelated context's silence stop this one from showing orders at all.
    String summary =
        distinctSkus.stream()
            .map(sku -> known.getOrDefault(sku, sku))
            .collect(Collectors.joining(", "));

    return new OrderListItem(
        fact.orderId(),
        fact.customerId(),
        fact.status(),
        fact.placedAt(),
        fact.paidAt(),
        fact.skusInOrder().size(),
        fact.totalMinor(),
        summary,
        clock.instant());
  }
}
