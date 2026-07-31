package com.example.ordering.application.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.UnitOfWork;
import com.aipersimmon.ddd.cqrs.spring.PrecheckCommandInterceptor;
import com.aipersimmon.ddd.cqrs.spring.RegistryCommandBus;
import com.aipersimmon.ddd.cqrs.spring.TransactionCommandInterceptor;
import com.example.ordering.application.fulfilment.FulfilmentTrigger;
import com.example.ordering.application.order.StockAvailabilityGateway.Availability;
import com.example.ordering.domain.customer.Customer;
import com.example.ordering.domain.customer.CustomerId;
import com.example.ordering.domain.customer.Customers;
import com.example.ordering.domain.order.Order;
import com.example.ordering.domain.order.OrderId;
import com.example.ordering.domain.order.Orders;
import com.example.ordering.domain.shared.Money;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The availability precheck must run <em>outside</em> the command's write transaction
 * (issue-00141). It is a synchronous cross-context call, and the gateway's own javadoc promises it
 * will one day be an HTTP client behind the same interface — at which point a precheck that runs
 * inside the transaction holds a database connection hostage to a remote call, and a slow inventory
 * service amplifies into an exhausted ordering connection pool. The check is advisory (the
 * authoritative reservation is asynchronous and compensable), so it contributes nothing to the
 * transaction it used to occupy.
 *
 * <p>Assembled by hand from the real bus and the real transaction interceptor, with a boundary-
 * marking {@link UnitOfWork} standing in for the transaction manager: whatever runs inside {@code
 * execute} is what would hold the connection in production.
 */
class AvailabilityPrecheckTransactionBoundaryTest {

  private final BoundaryMarkingUnitOfWork unitOfWork = new BoundaryMarkingUnitOfWork();
  private final BoundaryRecordingGateway gateway = new BoundaryRecordingGateway(unitOfWork);

  private CommandBus bus() {
    PlaceOrderHandler handler =
        new PlaceOrderHandler(
            new SavingOrders(),
            new OneCustomer(),
            () -> "order-1",
            fulfilmentTriggerNeverReached(),
            lines -> com.example.ordering.domain.order.ReviewRequirement.required(Set.of("test")));
    return new RegistryCommandBus(
        List.of(handler),
        List.of(
            new PrecheckCommandInterceptor(List.of(new StockAvailabilityPrecheck(gateway))),
            new TransactionCommandInterceptor(unitOfWork)),
        () -> "cmd-1");
  }

  @Test
  void theAvailabilityCheckRunsOutsideTheWriteTransaction() {
    bus().send(new PlaceOrder("cust-1", List.of(new PlaceOrder.Line("SKU-1", 2, 1500, "USD"))));

    assertEquals(1, gateway.calls.get(), "the check must still happen exactly once");
    assertNotNull(gateway.sawTransaction, "the gateway was never called");
    assertFalse(
        gateway.sawTransaction,
        "the advisory availability check must not hold the write transaction: behind this port is"
            + " (eventually) a remote call, and a transaction waiting on a remote call is a"
            + " database connection waiting on a remote call");
  }

  @Test
  void anUnavailableSkuStillRejectsTheOrderBeforeAnythingIsWritten() {
    gateway.unavailable = List.of("SKU-1");

    assertThrows(
        DomainException.class,
        () ->
            bus()
                .send(
                    new PlaceOrder(
                        "cust-1", List.of(new PlaceOrder.Line("SKU-1", 2, 1500, "USD")))));

    assertFalse(unitOfWork.everEntered, "a hopeless order must be refused before a transaction");
  }

  /** Marks the transaction boundary the way {@code TransactionTemplateUnitOfWork} would. */
  private static final class BoundaryMarkingUnitOfWork implements UnitOfWork {
    private boolean inTransaction;
    private boolean everEntered;

    @Override
    public <T> T execute(java.util.function.Supplier<T> work) {
      inTransaction = true;
      everEntered = true;
      try {
        return work.get();
      } finally {
        inTransaction = false;
      }
    }
  }

  private static final class BoundaryRecordingGateway implements StockAvailabilityGateway {
    private final BoundaryMarkingUnitOfWork unitOfWork;
    private final AtomicInteger calls = new AtomicInteger();
    private Boolean sawTransaction;
    private List<String> unavailable = List.of();

    private BoundaryRecordingGateway(BoundaryMarkingUnitOfWork unitOfWork) {
      this.unitOfWork = unitOfWork;
    }

    @Override
    public Availability check(List<String> skus) {
      calls.incrementAndGet();
      sawTransaction = unitOfWork.inTransaction;
      return new Availability(unavailable.isEmpty(), unavailable);
    }
  }

  private static final class OneCustomer implements Customers {
    @Override
    public Optional<Customer> findById(CustomerId id) {
      return Optional.of(new Customer(id, "Test Customer", Money.of(1_000_000, "USD")));
    }

    @Override
    public void save(Customer customer) {}
  }

  private static final class SavingOrders implements Orders {
    @Override
    public void save(Order order) {}

    @Override
    public Optional<Order> findById(OrderId id) {
      return Optional.empty();
    }
  }

  private static FulfilmentTrigger fulfilmentTriggerNeverReached() {
    // The review-required path never begins fulfilment, so the trigger's collaborators are inert.
    return new FulfilmentTrigger(
        new Orders() {
          @Override
          public void save(Order order) {
            throw new AssertionError("fulfilment must not begin for a review-held order");
          }

          @Override
          public Optional<Order> findById(OrderId id) {
            return Optional.empty();
          }
        },
        null,
        Clock.systemUTC(),
        Duration.ofMinutes(1));
  }
}
