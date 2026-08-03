package com.example.samples.s18;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import com.example.samples.s18.ordering.domain.Order;
import com.example.samples.s18.ordering.domain.OrderId;
import com.example.samples.s18.ordering.domain.OrderStatus;
import com.example.samples.s18.ordering.domain.Orders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Layer 4 — the one thing a double cannot answer.
 *
 * <p>What is under test here is the SQL, the version predicate the interceptor appends and the mapping
 * both ways. An in-memory repository would pass every assertion below while telling you nothing, which
 * is the reason to spend a container on exactly this layer and no other.
 *
 * <p>The container comes from {@code PostgresServiceConnection}: one image, shared across every test
 * class that imports it, because Spring reuses a context whose configuration matches. Two test classes
 * with different {@code properties} are two contexts and therefore two containers — worth knowing
 * before adding an override.
 */
@SpringBootTest
@Import(PostgresServiceConnection.class)
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class OrderPersistenceTest {

  @Autowired private Orders orders;
  @Autowired private TransactionTemplate tx;

  @Test
  void anOrderSurvivesARoundTrip() {
    OrderId id = new OrderId("order-p1");
    tx.executeWithoutResult(status -> orders.save(Order.place(id, "customer-1", 2500)));

    Order loaded = tx.execute(status -> orders.findById(id).orElseThrow());

    assertThat(loaded.customerId()).isEqualTo("customer-1");
    assertThat(loaded.amountCents()).isEqualTo(2500);
    assertThat(loaded.status()).isEqualTo(OrderStatus.PLACED);
    assertThat(loaded.version()).isEqualTo(1L);
  }

  /**
   * The container earns its keep twice here. This assertion cannot be made anywhere else: the
   * subscriber, the bus, the second handler and the transaction all have to be real for it to hold —
   * and writing this test is how the amount below came to be 50_000 rather than 100. A small order is
   * auto-confirmed by a subscriber the test never mentions, which a unit test with doubles would never
   * have shown, because a double only does what the test wired.
   */
  @Test
  void aSmallOrderIsAutoConfirmedByASubscriberNobodyCalled() {
    OrderId id = new OrderId("order-p3");

    tx.executeWithoutResult(status -> orders.save(Order.place(id, "customer-1", 100)));

    Order loaded = tx.execute(status -> orders.findById(id).orElseThrow());
    assertThat(loaded.status()).isEqualTo(OrderStatus.CONFIRMED);
  }

  @Test
  void theVersionPredicateIsReallyThere() {
    OrderId id = new OrderId("order-p2");
    // Above the auto-confirm threshold, so the order stays PLACED and this test is about one thing.
    tx.executeWithoutResult(status -> orders.save(Order.place(id, "customer-1", 50_000)));
    Order first = tx.execute(status -> orders.findById(id).orElseThrow());
    Order second = tx.execute(status -> orders.findById(id).orElseThrow());

    tx.executeWithoutResult(
        status -> {
          first.confirm();
          orders.save(first);
        });

    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      second.confirm();
                      orders.save(second);
                    }))
        .isInstanceOf(OptimisticLockingFailureException.class);
  }
}
