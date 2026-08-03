package com.example.samples.s18.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.tenancy.Tenants;
import com.aipersimmon.ddd.test.RecordingIntegrationEvents;
import com.example.samples.s18.ordering.api.OrderPlaced;
import com.example.samples.s18.ordering.domain.Order;
import com.example.samples.s18.ordering.domain.OrderId;
import com.example.samples.s18.ordering.domain.OrderStatus;
import com.example.samples.s18.ordering.domain.Orders;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Layer 2 — the application layer, on the library's in-memory doubles.
 *
 * <p>These are the tests that carry most of the value per second spent: they cover the use case's own
 * decisions (what gets saved, what gets announced, what is refused) at unit-test speed. A handler that
 * needs a container to be tested is usually a handler with infrastructure knowledge in it.
 */
class HandlerWithDoublesTest {

  private final InMemoryOrders orders = new InMemoryOrders();
  private final RecordingIntegrationEvents events = new RecordingIntegrationEvents();
  private final CommandContext context = CommandContext.root(Tenants.ROOT, "message-1");

  @Test
  void placingSavesTheOrderAndAnnouncesIt() {
    PlaceOrderHandler handler = new PlaceOrderHandler(orders, events, () -> "order-1");

    String id = handler.handle(new PlaceOrder("customer-1", 2500), context);

    assertThat(id).isEqualTo("order-1");
    assertThat(orders.saved).containsExactly("order-1");
    // eventsOf keeps the assertion readable and type-safe.
    assertThat(events.eventsOf(OrderPlaced.class))
        .containsExactly(new OrderPlaced("order-1", "customer-1", 2500));
  }

  @Test
  void theAnnouncementCarriesTheEnvelopeAContextWouldHaveBuilt() {
    PlaceOrderHandler handler = new PlaceOrderHandler(orders, events, () -> "order-2");

    handler.handle(new PlaceOrder("customer-1", 100), context);

    // The double builds a real EventEnvelope rather than storing the payload, so the test can assert
    // what a consumer would actually receive — and an event class missing @EventType fails here.
    assertThat(events.envelopes()).hasSize(1);
    assertThat(events.envelopes().get(0).type()).isEqualTo("s18.ordering.order-placed");
    assertThat(events.envelopes().get(0).version()).isEqualTo(1);
    assertThat(events.envelopes().get(0).tenantId()).isEqualTo(Tenants.ROOT.value());
  }

  @Test
  void confirmingAnUnknownOrderIsNotFound() {
    ConfirmOrderHandler handler = new ConfirmOrderHandler(orders);

    assertThatThrownBy(() -> handler.handle(new ConfirmOrder("missing"), context))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessageContaining("missing");
  }

  @Test
  void confirmingAdvancesTheOrderAndSavesIt() {
    orders.store.put(
        "order-3",
        Order.reconstitute(new OrderId("order-3"), "customer-1", 100, OrderStatus.PLACED, 1L));
    ConfirmOrderHandler handler = new ConfirmOrderHandler(orders);

    handler.handle(new ConfirmOrder("order-3"), context);

    assertThat(orders.store.get("order-3").status()).isEqualTo(OrderStatus.CONFIRMED);
    assertThat(orders.saved).containsExactly("order-3");
  }

  /**
   * The one double the library does not ship, because a repository port is the application's own
   * vocabulary. Keep it this dumb: a fake that grows behaviour becomes a second implementation to
   * maintain and to be wrong in.
   */
  private static final class InMemoryOrders implements Orders {
    private final Map<String, Order> store = new HashMap<>();
    private final java.util.List<String> saved = new java.util.ArrayList<>();

    @Override
    public Optional<Order> findById(OrderId id) {
      return Optional.ofNullable(store.get(id.value()));
    }

    @Override
    public void save(Order order) {
      store.put(order.id().value(), order);
      saved.add(order.id().value());
    }
  }
}
