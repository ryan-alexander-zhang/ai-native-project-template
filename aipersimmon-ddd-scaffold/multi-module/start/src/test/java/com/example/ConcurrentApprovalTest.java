package com.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.example.ordering.domain.order.Order;
import com.example.ordering.domain.order.OrderId;
import com.example.ordering.domain.order.Orders;
import com.example.ordering.infrastructure.persistence.order.MyBatisOrders;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
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
import org.junit.jupiter.api.BeforeEach;
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
 * Two operators approve the same held order at the same moment: one wins with 204, the other is
 * refused with 409, and nothing the loser did survives.
 *
 * <p>This is the missing end of the optimistic-locking chain. {@code ConcurrentAggregateWriteTest}
 * proves the version predicate refuses a stale write; the library proves each translation hop. What
 * nothing proved until now is that the whole path holds together over real HTTP — {@code
 * OptimisticLockingFailureException} → {@code ConcurrencyTranslationCommandInterceptor} → {@code
 * ConcurrencyConflictException} → the problem advice → a 409 document. That chain was designed
 * before aggregates had versions at all, so for a long time nothing could reach it.
 *
 * <h2>Why the loser's 409 has no code</h2>
 *
 * <p>The two 409s this endpoint can produce are not the same answer, and the test distinguishes
 * them:
 *
 * <ul>
 *   <li><strong>Approving an order that is not awaiting review</strong> — a domain conflict. It
 *       carries {@code type=/problems/resource-conflict} and the stable code {@code
 *       ordering.order-not-awaiting-review} (see {@code ExceptionContractTest}). Retrying will not
 *       help.
 *   <li><strong>Losing an optimistic-locking race</strong> — the case here. {@code
 *       ConcurrencyConflictException} is raised without an {@code ErrorCode}, so the problem falls
 *       back to {@code about:blank} with no {@code code} at all. Retrying is exactly what a client
 *       should do.
 * </ul>
 *
 * <p>Asserting the absence of a code is therefore what pins <em>which</em> conflict happened — and
 * it also records an awkwardness worth knowing: the more actionable of the two 409s is the less
 * machine-readable one.
 *
 * <h2>Why this is deterministic</h2>
 *
 * <p>Racing two HTTP requests and hoping they collide would pass either way — if they did not
 * overlap, the second would still get 409, just the domain one. So the race is staged rather than
 * hoped for: a repository decorator holds both requests at a barrier <em>after</em> each has loaded
 * the order, so both hold version 1 before either writes. From there PostgreSQL does the rest: the
 * second UPDATE waits on the winner's row lock, then matches no row, and the version predicate
 * turns that into the exception the chain is built on.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "aipersimmon.ddd.process-manager.effect-relay.enabled=false",
      "aipersimmon.ddd.process-manager.deadline-worker.enabled=false",
      "aipersimmon.ddd.outbox.relay.enabled=false",
    })
@Import({TestInfrastructure.class, ConcurrentApprovalTest.StageTheRace.class})
class ConcurrentApprovalTest {

  private static final String TENANT = "acme";

  private final TestRestTemplate http;
  private final JdbcTemplate jdbc;

  ConcurrentApprovalTest(@Autowired TestRestTemplate http, @Autowired JdbcTemplate jdbc) {
    this.http = http;
    this.jdbc = jdbc;
  }

  @BeforeEach
  void seedTenant() {
    // The Flyway seed lives under the 'demo' tenant; this tenant needs its own copy. SKU-RESTRICTED
    // is the
    // one ManualReviewPolicy flags, which is what parks the order in AWAITING_REVIEW.
    jdbc.update(
        "INSERT INTO ordering.customers (id, name, credit_minor, currency, tenant_id)"
            + " VALUES ('CUST-1', 'Acme', 100000, 'USD', ?)"
            + " ON CONFLICT (tenant_id, id) DO NOTHING",
        TENANT);
    jdbc.update(
        "INSERT INTO inventory.stocks (sku, available, tenant_id)"
            + " VALUES ('SKU-RESTRICTED', 10, ?)"
            + " ON CONFLICT (tenant_id, sku) DO NOTHING",
        TENANT);
  }

  @Test
  void oneApprovalWinsAndTheOtherGets409() throws Exception {
    String order = placeOrderHeldForReview();
    long outboxBefore = outboxRows();

    List<ResponseEntity<JsonNode>> responses = approveTwiceAtOnce(order + "/approve-review");
    List<Integer> statuses =
        responses.stream().map(r -> r.getStatusCode().value()).sorted().toList();

    assertEquals(List.of(204, 409), statuses, "exactly one approval may win");

    JsonNode loser =
        responses.stream()
            .filter(r -> r.getStatusCode().value() == 409)
            .findFirst()
            .orElseThrow()
            .getBody();
    assertNotNull(loser, "a 409 must carry a problem document");
    assertEquals(409, loser.path("status").asInt());
    assertEquals(
        "about:blank",
        loser.path("type").asText(),
        "an optimistic-lock conflict is the code-less 409; a domain conflict would name its type");
    assertFalse(
        loser.hasNonNull("code"),
        "a coded 409 here would mean the loser lost to the domain guard, not to the version "
            + "predicate — the race did not happen and this test proves nothing");

    // The winner's write stands, and the loser left nothing behind: its transaction rolled back,
    // so the integration event it had already written to the outbox went with it.
    // The winner's approval stands: the order left AWAITING_REVIEW. It stops at
    // READY_FOR_FULFILMENT rather than FULFILMENT_IN_PROGRESS because the relays are off here and
    // nothing has reserved stock — that transition now waits for the reservation (issue-00070).
    assertEquals("READY_FOR_FULFILMENT", statusOf(order));
    assertEquals(
        outboxBefore + 1,
        outboxRows(),
        "the losing transaction must not leave its integration event in the outbox");
  }

  private String placeOrderHeldForReview() {
    String body =
        """
        {"customerId":"CUST-1",
         "lines":[{"sku":"SKU-RESTRICTED","quantity":1,"unitAmountMinor":100,"currency":"USD"}]}
        """;
    HttpHeaders headers = tenantHeader();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<Void> placed =
        http.postForEntity("/orders", new HttpEntity<>(body, headers), Void.class);
    assertEquals(201, placed.getStatusCode().value());
    URI location = placed.getHeaders().getLocation();
    assertNotNull(location, "a placed order must advertise its URI");
    // The path only: TestRestTemplate resolves a relative String against the random port, which a
    // URI argument would bypass.
    String order = location.getPath();
    assertEquals("AWAITING_REVIEW", statusOf(order), "SKU-RESTRICTED must be held for review");
    return order;
  }

  /** Fires both approvals so that each has loaded the order before either writes. */
  private List<ResponseEntity<JsonNode>> approveTwiceAtOnce(String approve) throws Exception {
    HttpEntity<Void> request = new HttpEntity<>(tenantHeader());
    Callable<ResponseEntity<JsonNode>> call =
        () -> http.exchange(approve, HttpMethod.POST, request, JsonNode.class);

    ExecutorService pool = Executors.newFixedThreadPool(2);
    StageTheRace.GATE.set(new CyclicBarrier(2));
    try {
      List<Future<ResponseEntity<JsonNode>>> pending =
          List.of(pool.submit(call), pool.submit(call));
      List<ResponseEntity<JsonNode>> responses = new ArrayList<>();
      for (Future<ResponseEntity<JsonNode>> future : pending) {
        responses.add(future.get(30, TimeUnit.SECONDS));
      }
      return responses;
    } finally {
      StageTheRace.GATE.set(null);
      pool.shutdownNow();
    }
  }

  private String statusOf(String order) {
    JsonNode snapshot =
        http.exchange(order, HttpMethod.GET, new HttpEntity<>(tenantHeader()), JsonNode.class)
            .getBody();
    return snapshot == null ? null : snapshot.path("status").asText();
  }

  private HttpHeaders tenantHeader() {
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Tenant-Id", TENANT);
    return headers;
  }

  private long outboxRows() {
    Long count = jdbc.queryForObject("select count(*) from aipersimmon_outbox", Long.class);
    return count == null ? 0 : count;
  }

  /**
   * Holds every {@code findById} at a barrier while the gate is armed, so two requests reach their
   * write with the same loaded version. It only sequences — the real repository still performs the
   * version-checked write, which is the thing under test.
   */
  @TestConfiguration(proxyBeanMethods = false)
  static class StageTheRace {

    static final AtomicReference<CyclicBarrier> GATE = new AtomicReference<>();

    @Bean
    @Primary
    Orders bothReadBeforeEitherWrites(MyBatisOrders delegate) {
      return new Orders() {
        @Override
        public void save(Order order) {
          delegate.save(order);
        }

        @Override
        public Optional<Order> findById(OrderId id) {
          Optional<Order> loaded = delegate.findById(id);
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
