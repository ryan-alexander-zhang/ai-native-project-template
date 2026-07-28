package com.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.Tenants;
import com.example.ordering.application.order.CancelOrder;
import com.example.ordering.domain.customer.Customer;
import com.example.ordering.domain.customer.CustomerId;
import com.example.ordering.domain.customer.Customers;
import com.example.ordering.domain.order.CancellationReason;
import com.example.ordering.domain.order.OrderId;
import com.example.ordering.domain.order.ReservationFailureRef;
import com.example.ordering.infrastructure.persistence.customer.MyBatisCustomers;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The credit limit is enforced, not merely consulted (issue-00071).
 *
 * <p>It used to be neither strongly nor eventually consistent, but a third thing: a comparison
 * against a stale snapshot, presented throughout the stack as a hard rule — its own error code, its
 * own problem type, a documented top-up flow — with nothing anywhere enforcing it. Two failures
 * came out of that, and they are independent:
 *
 * <ul>
 *   <li><strong>It was not cumulative.</strong> {@code canAfford} compared one order's total
 *       against the whole limit, so orders of 60,000 and 60,000 both passed a limit of 100,000 with
 *       no concurrency involved at all. What was called a credit limit behaved as a per-order cap.
 *   <li><strong>It was not serialisable.</strong> Nothing wrote to {@code ordering.customers} — the
 *       port had no {@code save} and {@code V3} deliberately left the table unversioned — so there
 *       was no contention point and no number of simultaneous placements could ever conflict.
 * </ul>
 *
 * <p>Strong consistency was chosen over eventual: {@code Customer} and {@code Order} share a
 * database, so the invariant spanning them is held in one transaction rather than chased afterwards
 * by a reconciliation process that would itself have to be built and proven. The trade-off is the
 * same one {@code ReserveStockHandler} already argues for stock.
 *
 * <p>The third test is the one that would rot first. Credit committed on placement has to come back
 * on <em>every</em> route to cancellation, and a missed route fails silently — no error, just a
 * customer slowly locked out by orders that no longer exist.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "aipersimmon.ddd.process-manager.effect-relay.enabled=false",
      "aipersimmon.ddd.process-manager.deadline-worker.enabled=false",
      "aipersimmon.ddd.outbox.relay.enabled=false",
    })
@Import({TestInfrastructure.class, CreditLimitTest.StageTheRace.class})
class CreditLimitTest {

  private static final String TENANT = "acme";

  private final TestRestTemplate http;
  private final JdbcTemplate jdbc;
  private final CommandBus commandBus;

  CreditLimitTest(
      @Autowired TestRestTemplate http,
      @Autowired JdbcTemplate jdbc,
      @Autowired CommandBus commandBus) {
    this.http = http;
    this.jdbc = jdbc;
    this.commandBus = commandBus;
  }

  /** The per-order cap that was wearing a credit limit's name. No concurrency needed. */
  @Test
  void creditAlreadyCommittedCountsAgainstTheNextOrder() {
    String customer = customerWithLimit("CUST-CUMULATIVE", 100_000);

    assertEquals(201, place(customer, 600).getStatusCode().value(), "60000 of a 100000 limit");

    ResponseEntity<JsonNode> refused = place(customer, 600);
    assertEquals(
        422,
        refused.getStatusCode().value(),
        "a further 60000 exceeds what is left, though it is under the limit on its own");
    assertNotNull(refused.getBody());
    assertEquals("ordering.credit-exceeded", refused.getBody().path("code").asText());

    assertEquals(60_000, usedCreditOf(customer), "and exactly one order's worth is committed");
  }

  /**
   * Two placements that both pass the check on the same snapshot. The loser gets a 409 from the
   * version predicate rather than a 422 — it did not exceed anything it could see, it simply lost
   * the race, and retrying is the right response (at which point it will get its 422).
   */
  @Test
  void twoSimultaneousOrdersCannotBetweenThemExceedTheLimit() throws Exception {
    String customer = customerWithLimit("CUST-RACE", 100_000);

    List<Integer> statuses = placeTwiceAtOnce(customer, 600);

    assertEquals(List.of(201, 409), statuses, "only one of the two may commit");
    assertEquals(
        60_000,
        usedCreditOf(customer),
        "the loser's reservation rolled back with its transaction — two would be 120000, over the"
            + " limit, which is exactly what was possible before the customer row was versioned");
  }

  /** Cancellation returns the credit, by every route that reaches {@code Order.cancel}. */
  @Test
  void cancellingAnOrderReturnsItsCreditByEveryRoute() {
    String customer = customerWithLimit("CUST-RELEASE", 100_000);

    // Route 1: the process manager's compensation entry point, which is how a declined payment, a
    // failed reservation and an expired payment deadline all end up releasing. The reason carries
    // its evidence because the aggregate will not cancel a fulfilling order without it.
    String compensated = orderIdOf(place(customer, 300));
    assertEquals(30_000, usedCreditOf(customer));
    TenantContext.runAs(
        Tenants.of(TENANT),
        () ->
            commandBus.send(
                new CancelOrder(
                    compensated,
                    new CancellationReason.InventoryUnavailable(
                        new ReservationFailureRef(
                            "failure-1",
                            new OrderId(compensated),
                            "inventory.insufficient-stock",
                            "out of stock")))));
    assertEquals(0, usedCreditOf(customer), "compensation must give the credit back");

    // Route 2: the customer cancelling their own order over HTTP. A held-for-review order, because
    // that is the only state the self-cancel window is currently reachable in (issue-00070).
    String selfCancelled = orderIdOf(placeRestricted(customer));
    assertEquals(10_000, usedCreditOf(customer));
    assertEquals(
        204,
        http.exchange(
                "/orders/" + selfCancelled + "/cancel?customerId=" + customer,
                HttpMethod.POST,
                new HttpEntity<>(jsonHeaders()),
                Void.class)
            .getStatusCode()
            .value());
    assertEquals(0, usedCreditOf(customer), "self-cancellation must give the credit back too");

    // Route 3: a reviewer rejecting a held order (issue-00082). It is the newest route, and the
    // one most likely to have been forgotten — the credit was committed at placement, before
    // anyone looked at the order, so a rejection that did not release would shrink the limit for
    // an order the business explicitly refused.
    String rejected = orderIdOf(placeRestricted(customer));
    assertEquals(10_000, usedCreditOf(customer));
    assertEquals(
        204,
        http.exchange(
                "/orders/" + rejected + "/reject-review",
                HttpMethod.POST,
                new HttpEntity<>(jsonHeaders()),
                Void.class)
            .getStatusCode()
            .value());
    assertEquals(0, usedCreditOf(customer), "a rejected review must give the credit back as well");

    // The proof that release is real rather than cosmetic: the full limit is committable again.
    assertEquals(201, place(customer, 1000).getStatusCode().value());
  }

  /** A customer of this tenant with a known limit and nothing committed. */
  private String customerWithLimit(String id, long limitMinor) {
    jdbc.update(
        "INSERT INTO ordering.customers (id, name, credit_minor, currency, tenant_id)"
            + " VALUES (?, 'Acme', ?, 'USD', ?)"
            + " ON CONFLICT (tenant_id, id)"
            + " DO UPDATE SET credit_minor = EXCLUDED.credit_minor, used_minor = 0",
        id,
        limitMinor,
        TENANT);
    jdbc.update(
        "INSERT INTO inventory.stocks (sku, available, tenant_id)"
            + " VALUES ('SKU-1', 100000, ?), ('SKU-RESTRICTED', 100000, ?)"
            + " ON CONFLICT (tenant_id, sku) DO NOTHING",
        TENANT,
        TENANT);
    return id;
  }

  private long usedCreditOf(String customer) {
    Long used =
        jdbc.queryForObject(
            "SELECT used_minor FROM ordering.customers WHERE tenant_id = ? AND id = ?",
            Long.class,
            TENANT,
            customer);
    return used == null ? -1 : used;
  }

  /** Places an order of {@code quantity x 100} minor units. */
  private ResponseEntity<JsonNode> place(String customer, int quantity) {
    return placeSku(customer, "SKU-1", quantity);
  }

  /** SKU-RESTRICTED is held for manual review, which is where self-cancel is reachable. */
  private ResponseEntity<JsonNode> placeRestricted(String customer) {
    return placeSku(customer, "SKU-RESTRICTED", 100);
  }

  private ResponseEntity<JsonNode> placeSku(String customer, String sku, int quantity) {
    String body =
        """
        {"customerId":"%s",
         "lines":[{"sku":"%s","quantity":%d,"unitAmountMinor":100,"currency":"USD"}]}
        """
            .formatted(customer, sku, quantity);
    return http.postForEntity("/orders", new HttpEntity<>(body, jsonHeaders()), JsonNode.class);
  }

  private static String orderIdOf(ResponseEntity<JsonNode> placed) {
    assertEquals(201, placed.getStatusCode().value());
    String location = placed.getHeaders().getLocation().getPath();
    return location.substring(location.lastIndexOf('/') + 1);
  }

  /** Fires both placements so each has loaded the customer before either writes. */
  private List<Integer> placeTwiceAtOnce(String customer, int quantity) throws Exception {
    Callable<ResponseEntity<JsonNode>> call = () -> place(customer, quantity);

    ExecutorService pool = Executors.newFixedThreadPool(2);
    StageTheRace.GATE.set(new CyclicBarrier(2));
    try {
      List<Future<ResponseEntity<JsonNode>>> pending =
          List.of(pool.submit(call), pool.submit(call));
      List<Integer> statuses = new ArrayList<>();
      for (Future<ResponseEntity<JsonNode>> future : pending) {
        statuses.add(future.get(30, TimeUnit.SECONDS).getStatusCode().value());
      }
      return statuses.stream().sorted().toList();
    } finally {
      StageTheRace.GATE.set(null);
      pool.shutdownNow();
    }
  }

  private HttpHeaders jsonHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Tenant-Id", TENANT);
    headers.setContentType(MediaType.APPLICATION_JSON);
    return headers;
  }

  /**
   * Holds every customer {@code findById} at a barrier while the gate is armed, so both placements
   * reach {@code reserveCredit} holding the same {@code usedCredit} and the same version. It only
   * sequences; the real repository still performs the version-checked write, which is the thing
   * under test. Same technique as {@code ConcurrentApprovalTest}, one aggregate over.
   */
  @TestConfiguration(proxyBeanMethods = false)
  static class StageTheRace {

    static final AtomicReference<CyclicBarrier> GATE = new AtomicReference<>();

    @Bean
    @Primary
    Customers bothReadBeforeEitherWrites(MyBatisCustomers delegate) {
      return new Customers() {
        @Override
        public void save(Customer customer) {
          delegate.save(customer);
        }

        @Override
        public Optional<Customer> findById(CustomerId id) {
          Optional<Customer> loaded = delegate.findById(id);
          CyclicBarrier gate = GATE.get();
          if (gate != null) {
            try {
              gate.await(30, TimeUnit.SECONDS);
            } catch (Exception e) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException("the staged race never met at the barrier", e);
            }
          }
          return loaded;
        }
      };
    }
  }
}
