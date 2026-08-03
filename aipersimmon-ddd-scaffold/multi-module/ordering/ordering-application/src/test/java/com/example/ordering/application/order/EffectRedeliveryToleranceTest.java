package com.example.ordering.application.order;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.tenancy.Tenants;
import com.example.ordering.domain.customer.Customer;
import com.example.ordering.domain.customer.CustomerId;
import com.example.ordering.domain.customer.Customers;
import com.example.ordering.domain.order.CancellationReason;
import com.example.ordering.domain.order.LineData;
import com.example.ordering.domain.order.Order;
import com.example.ordering.domain.order.OrderId;
import com.example.ordering.domain.order.OrderStatus;
import com.example.ordering.domain.order.Orders;
import com.example.ordering.domain.order.ReservationFailureRef;
import com.example.ordering.domain.order.ReviewRequirement;
import com.example.ordering.domain.shared.Money;
import com.example.ordering.domain.shared.Sku;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Process-manager effects are dispatched at-least-once ({@code ProcessEffectRelay}: a crash between
 * the command's commit and the delivered mark redelivers the same command), so every handler an
 * effect targets must tolerate its own success arriving twice. {@code BeginFulfilmentHandler}
 * always did; these tests pin the same contract onto {@code ConfirmOrderHandler} and {@code
 * CancelOrderHandler}, which used to let the aggregate refuse the duplicate — turning each
 * redelivery into a poison effect the relay retries to dead-letter.
 */
class EffectRedeliveryToleranceTest {

  private static final CustomerId CUSTOMER = new CustomerId("cust-1");
  private static final CommandContext CONTEXT = CommandContext.root(Tenants.of("demo"), "msg-1");

  private final InMemoryOrders orders = new InMemoryOrders();
  private final RecordingCustomers customers = new RecordingCustomers();

  private Order storedOrderUnderFulfilment(OrderId id) {
    Order order =
        Order.place(
            id,
            CUSTOMER,
            List.of(new LineData(new Sku("SKU-1"), 1, Money.of(1_000, "USD"))),
            ReviewRequirement.notRequired());
    order.beginFulfilment();
    orders.save(order);
    return order;
  }

  @Test
  void aRedeliveredConfirmOrderIsANoOpOnceTheOrderIsConfirmed() {
    OrderId id = new OrderId("order-1");
    storedOrderUnderFulfilment(id);
    ConfirmOrderHandler handler = new ConfirmOrderHandler(orders);

    handler.handle(new ConfirmOrder(id.value()), CONTEXT);
    int savesAfterFirstDelivery = orders.saves;

    assertDoesNotThrow(() -> handler.handle(new ConfirmOrder(id.value()), CONTEXT));
    assertEquals(OrderStatus.CONFIRMED, orders.findById(id).orElseThrow().status());
    assertEquals(
        savesAfterFirstDelivery, orders.saves, "a redelivery must not write the aggregate again");
  }

  @Test
  void aRedeliveredCancelOrderIsANoOpAndReleasesCreditOnlyOnce() {
    OrderId id = new OrderId("order-1");
    storedOrderUnderFulfilment(id);
    CancelOrderHandler handler = new CancelOrderHandler(orders, new CustomerCredit(customers));
    CancelOrder command =
        new CancelOrder(
            id.value(),
            new CancellationReason.InventoryUnavailable(
                new ReservationFailureRef("fail-1", id, "out_of_stock", "SKU-1")));

    handler.handle(command, CONTEXT);
    assertEquals(OrderStatus.CANCELLED, orders.findById(id).orElseThrow().status());
    assertEquals(1, customers.creditWrites);

    assertDoesNotThrow(() -> handler.handle(command, CONTEXT));
    assertEquals(
        1,
        customers.creditWrites,
        "a redelivery must not release the order's credit a second time");
  }

  /** Stores the live aggregate instance and drains its events, as the real repository does. */
  private static final class InMemoryOrders implements Orders {
    private final Map<String, Order> store = new HashMap<>();
    private int saves;

    @Override
    public void save(Order order) {
      saves++;
      order.drainDomainEvents();
      store.put(order.id().value(), order);
    }

    @Override
    public Optional<Order> findById(OrderId id) {
      return Optional.ofNullable(store.get(id.value()));
    }
  }

  /** Hands out a fresh customer with ample credit and counts the writes coming back. */
  private static final class RecordingCustomers implements Customers {
    private int creditWrites;

    @Override
    public Optional<Customer> findById(CustomerId id) {
      Customer customer = new Customer(id, "Test Customer", Money.of(1_000_000, "USD"));
      customer.reserveCredit(Money.of(1_000, "USD"));
      return Optional.of(customer);
    }

    @Override
    public void save(Customer customer) {
      creditWrites++;
    }
  }
}
