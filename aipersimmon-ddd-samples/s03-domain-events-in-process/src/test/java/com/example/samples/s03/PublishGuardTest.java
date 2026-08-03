package com.example.samples.s03;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.core.event.DomainEvent;
import com.example.samples.s03.ordering.domain.Order;
import com.example.samples.s03.ordering.domain.OrderId;
import com.example.samples.s03.ordering.domain.OrderPlaced;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The publish contract, at unit speed — no Spring, no database, because none of it is needed to
 * observe the drain or the guard.
 */
class PublishGuardTest {

  private static final OrderId ID = new OrderId("order-1");

  @Test
  void publishingDrainsTheAggregate() {
    Order order = Order.place(ID, "customer-1", true, 2500);
    Collecting events = new Collecting();

    assertThat(order.domainEvents()).hasSize(1);
    events.publishAndClear(order);

    // Drained, so a second save cannot publish the same fact twice.
    assertThat(order.domainEvents()).isEmpty();
    assertThat(events.published).hasSize(1);
    assertThat(events.published.get(0)).isInstanceOf(OrderPlaced.class);
  }

  @Test
  void asubscriberThatRecordsAnotherEventOnTheSameAggregateIsRefused() {
    Order order = Order.place(ID, "customer-1", true, 2500);
    // A subscriber that reaches back into the aggregate it was told about. It is refused, and the
    // message says why: the root was already persisted, so the state this new event announces was
    // never written — publishing it would describe something that did not happen, and dropping it
    // silently is how a domain fact goes missing.
    DomainEvents reachingBack =
        new DomainEvents() {
          @Override
          public void publish(DomainEvent event) {
            order.flagForReview("reached back from a subscriber");
          }
        };

    assertThatThrownBy(() -> reachingBack.publishAndClear(order))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("was already persisted")
        .hasMessageContaining("Make the change before the aggregate is saved");
  }

  @Test
  void thefactCarriesIdentitiesRatherThanTheAggregate() {
    Order order = Order.place(ID, "customer-9", false, 700);

    OrderPlaced fact = (OrderPlaced) order.domainEvents().get(0);

    // Everything a reaction needs, and no reference to the root — which is also what stops a
    // subscriber getting into the situation above.
    assertThat(fact.orderId()).isEqualTo(ID);
    assertThat(fact.customerId()).isEqualTo("customer-9");
    assertThat(fact.firstOrder()).isFalse();
    assertThat(fact.amountCents()).isEqualTo(700);
  }

  private static final class Collecting implements DomainEvents {
    private final List<DomainEvent> published = new ArrayList<>();

    @Override
    public void publish(DomainEvent event) {
      published.add(event);
    }
  }
}
