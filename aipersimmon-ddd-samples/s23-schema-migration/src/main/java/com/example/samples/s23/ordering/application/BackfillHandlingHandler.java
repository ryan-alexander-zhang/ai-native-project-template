package com.example.samples.s23.ordering.application;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s23.ordering.api.OrderHandlingDecided;
import com.example.samples.s23.ordering.domain.Order;
import com.example.samples.s23.ordering.domain.OrderId;
import com.example.samples.s23.ordering.domain.Orders;
import org.springframework.stereotype.Component;

/**
 * The backfill, and the reason it is a command rather than an UPDATE statement.
 *
 * <p>The criterion is short enough to remember: <strong>restating bytes that are already in the row is SQL;
 * deciding anything, or having to tell anyone, is a command.</strong> V2's address split is the first case —
 * the street and the city were already in {@code ship_to}, just badly shaped, so the migration split them and
 * no rule was involved. This is the second case twice over:
 *
 * <ul>
 *   <li>it <strong>decides</strong>, by a rule that lives in {@code Handling} and is also applied to every
 *       new order. In SQL it would be a {@code CASE WHEN} containing a copy of that rule, including the list
 *       of remote cities, drifting from the day the carrier adds an island.
 *   <li>it <strong>announces</strong>. The rows it touches are years old, and deciding their handling changes
 *       what downstream should believe about them. An UPDATE has nobody to tell.
 * </ul>
 *
 * <p>Two more properties come free from going through the command channel and would each have to be built by
 * hand in a script: the aggregate's invariants are checked (a backfill cannot write a state the domain would
 * refuse), and the events land in the outbox <em>in the same transaction</em> as the rows — so an interrupted
 * backfill has neither decided without announcing nor announced without deciding.
 *
 * <p>It returns how many it decided, so the caller can loop until zero. Only rows it actually changed are
 * announced, which is what makes running it again harmless: the second pass finds nothing, decides nothing
 * and publishes nothing.
 */
@Component
class BackfillHandlingHandler implements CommandHandler<BackfillHandling, Integer> {

  private final Orders orders;
  private final IntegrationEvents integrationEvents;

  BackfillHandlingHandler(Orders orders, IntegrationEvents integrationEvents) {
    this.orders = orders;
    this.integrationEvents = integrationEvents;
  }

  @Override
  public Integer handle(BackfillHandling command, CommandContext context) {
    int decided = 0;
    for (OrderId id : orders.undecidedHandling(command.batchSize())) {
      Order order = orders.find(id).orElseThrow();
      if (!order.decideHandling()) {
        continue;
      }
      orders.save(order);
      integrationEvents.publish(
          new OrderHandlingDecided(id.value(), order.handling().orElseThrow().name()), context);
      decided++;
    }
    return decided;
  }
}
