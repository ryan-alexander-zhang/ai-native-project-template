package com.example.samples.s01.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.tenancy.Tenants;
import com.example.samples.s01.ordering.domain.Order;
import com.example.samples.s01.ordering.domain.OrderId;
import com.example.samples.s01.ordering.domain.OrderLine;
import com.example.samples.s01.ordering.domain.OrderStatus;
import com.example.samples.s01.ordering.domain.Orders;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The application layer, tested against an in-memory port. No Spring, no container. */
class ConfirmOrderHandlerTest {

  private final InMemoryOrders orders = new InMemoryOrders();
  private final ConfirmOrderHandler handler = new ConfirmOrderHandler(orders);
  private final CommandContext context = CommandContext.root(Tenants.ROOT, "message-1");

  @Test
  void confirmingAPlacedOrderAdvancesItAndSavesIt() {
    Order order =
        Order.reconstitute(
            new OrderId("order-1"),
            "customer-1",
            List.of(new OrderLine("SKU-1", 1)),
            OrderStatus.PLACED,
            1L);
    orders.store.put("order-1", order);

    handler.handle(new ConfirmOrder("order-1"), context);

    assertThat(orders.store.get("order-1").status()).isEqualTo(OrderStatus.CONFIRMED);
    assertThat(orders.saved).containsExactly("order-1");
  }

  @Test
  void anUnknownOrderIsNotFound() {
    assertThatThrownBy(() -> handler.handle(new ConfirmOrder("missing"), context))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessageContaining("missing");
  }

  private static final class InMemoryOrders implements Orders {
    private final Map<String, Order> store = new HashMap<>();
    private final List<String> saved = new java.util.ArrayList<>();

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
