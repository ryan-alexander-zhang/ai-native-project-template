package com.example.samples.s12.ordering.application;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Update the replica, then recompute every list row that displayed the old name.
 *
 * <p><strong>The second half is the cost of denormalising somebody else's data, and it is not small.</strong>
 * One rename of a popular sku is one write to {@code s12_product_name} plus one recomputation per order that
 * ever contained it — unbounded, and growing for the lifetime of the product. That is the number to look at
 * before deciding a projection is cheap: the query load moves off the read path and reappears, amplified, on
 * the write path of an event you do not control the rate of.
 *
 * <p>Three ways a real deployment bounds it, none of them shown here because each is a different sample:
 * recompute only rows a customer is likely to look at, recompute lazily on read against a version marker, or
 * accept the stale name until the next natural rebuild. The point is that "just project it" has a bill.
 *
 * @return how many list rows were recomputed, so the caller and the tests can see the amplification rather
 *     than take this javadoc's word for it.
 */
@Component
class RecordProductNameHandler implements CommandHandler<RecordProductName, Integer> {

  private static final Logger log = LoggerFactory.getLogger(RecordProductNameHandler.class);

  private final ProductNames productNames;
  private final OrderFacts orderFacts;
  private final OrderListProjection projection;
  private final Clock clock;

  RecordProductNameHandler(
      ProductNames productNames,
      OrderFacts orderFacts,
      OrderListProjection projection,
      Clock clock) {
    this.productNames = productNames;
    this.orderFacts = orderFacts;
    this.projection = projection;
    this.clock = clock;
  }

  @Override
  public Integer handle(RecordProductName command, CommandContext context) {
    productNames.record(command.sku(), command.name(), clock.instant());

    List<String> affected = orderFacts.orderIdsContaining(command.sku());
    affected.forEach(projection::rebuild);
    log.info(
        "product {} renamed to '{}': {} list rows recomputed",
        command.sku(),
        command.name(),
        affected.size());
    return affected.size();
  }
}
