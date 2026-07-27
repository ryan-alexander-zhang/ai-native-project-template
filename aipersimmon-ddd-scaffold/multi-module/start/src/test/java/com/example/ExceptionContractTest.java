package com.example;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end contract for the exception model over HTTP: a business-rule violation renders an RFC
 * 9457 problem with the corrected status (422) and the stable domain code, and a missing aggregate
 * renders 404 — both driven through the real web stack.
 *
 * <p>With multi-tenancy enabled (design-00009) every request crosses the tenant-resolution filter
 * first, and {@code missing-policy=REJECT} would 400 a header-less call before it reaches the
 * controller. These requests therefore carry {@code X-Tenant-Id}, standing in for the real edge
 * (JWT/subdomain) that a deployment resolves the tenant from — the same trusted boundary that seeds
 * the tenant onto the command. The tenant's own {@code CUST-1} / {@code SKU-1} are seeded per test,
 * since reads and writes are now scoped to it and the Flyway seed lives under {@code __root__}.
 */
@SpringBootTest(
    properties = {
      "aipersimmon.ddd.process-manager.effect-relay.poll-delay=200ms",
      "aipersimmon.ddd.process-manager.deadline-worker.enabled=false",
      "aipersimmon.ddd.outbox.poll-delay-ms=200",
    })
@AutoConfigureMockMvc
@Import(TestInfrastructure.class)
class ExceptionContractTest {

  private static final String TENANT = "acme";

  private final MockMvc mvc;
  private final JdbcTemplate jdbc;

  ExceptionContractTest(@Autowired MockMvc mvc, @Autowired JdbcTemplate jdbc) {
    this.mvc = mvc;
    this.jdbc = jdbc;
  }

  @BeforeEach
  void seedTenant() {
    // The Flyway seed (CUST-1 / SKU-1) lives under __root__; this tenant needs its own copy so the
    // tenant-scoped reads and the availability gateway see them. Idempotent across the class's
    // tests.
    jdbc.update(
        "INSERT INTO ordering.customers (id, name, credit_minor, currency, tenant_id)"
            + " VALUES ('CUST-1', 'Acme', 100000, 'USD', ?)"
            + " ON CONFLICT (tenant_id, id) DO NOTHING",
        TENANT);
    jdbc.update(
        "INSERT INTO inventory.stocks (sku, available, tenant_id)"
            + " VALUES ('SKU-1', 10, ?)"
            + " ON CONFLICT (tenant_id, sku) DO NOTHING",
        TENANT);
  }

  @Test
  void creditExceededRendersProblemWith422AndCode() throws Exception {
    // CUST-1 is seeded with 100_000 credit; a 200_000 order exceeds it.
    String body =
        """
                {"customerId":"CUST-1",
                 "lines":[{"sku":"SKU-1","quantity":1,"unitAmountMinor":200000,"currency":"USD"}]}
                """;

    mvc.perform(
            post("/orders")
                .header("X-Tenant-Id", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.status").value(422))
        // CREDIT_EXCEEDED is overridden to its own problem type (client shows a top-up flow).
        .andExpect(jsonPath("$.type").value("/problems/insufficient-credit"))
        .andExpect(jsonPath("$.code").value("ordering.credit-exceeded"));
  }

  @Test
  void duplicateSkuViolatesAggregateRuleWith422AndCode() throws Exception {
    // Two lines with the same SKU breaks the Order.checkInvariant(OrderHasDistinctSkus) invariant.
    String body =
        """
                {"customerId":"CUST-1",
                 "lines":[{"sku":"SKU-1","quantity":1,"unitAmountMinor":100,"currency":"USD"},
                          {"sku":"SKU-1","quantity":2,"unitAmountMinor":100,"currency":"USD"}]}
                """;

    mvc.perform(
            post("/orders")
                .header("X-Tenant-Id", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.status").value(422))
        .andExpect(jsonPath("$.code").value("ordering.duplicate-sku"))
        // No override → rides the DOMAIN_RULE family type, distinguished by its code.
        .andExpect(jsonPath("$.type").value("/problems/domain-rule-violation"));
  }

  @Test
  void unknownCustomerRendersProblemWith404AndCode() throws Exception {
    String body =
        """
                {"customerId":"NOPE",
                 "lines":[{"sku":"SKU-1","quantity":1,"unitAmountMinor":100,"currency":"USD"}]}
                """;

    mvc.perform(
            post("/orders")
                .header("X-Tenant-Id", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.code").value("ordering.customer-not-found"));
  }

  @Test
  void unknownOrderOnApproveReviewRenders404() throws Exception {
    mvc.perform(post("/orders/NON-EXISTENT/approve-review").header("X-Tenant-Id", TENANT))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ordering.order-not-found"));
  }

  @Test
  void missingOrderReadRenders404() throws Exception {
    mvc.perform(get("/orders/NON-EXISTENT").header("X-Tenant-Id", TENANT))
        .andExpect(status().isNotFound());
  }

  @Test
  void unknownPathRenders404NotFallback500() throws Exception {
    // A path with no handler is a routing-level NoResourceFoundException. It must render the
    // proper 404 problem, not the catch-all 500 (issue-00045).
    mvc.perform(get("/no-such-endpoint").header("X-Tenant-Id", TENANT))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.status").value(404));
  }

  @Test
  void wrongMethodRenders405NotFallback500() throws Exception {
    // /orders is mapped for POST only; a GET is a method mismatch → 405, not 500 (issue-00045).
    mvc.perform(get("/orders").header("X-Tenant-Id", TENANT))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.status").value(405));
  }

  @Test
  void healthEndpointIsReachableAndUp() throws Exception {
    // Actuator on the classpath (issue-00045): the health probe resolves to the real endpoint
    // and reports UP against the Testcontainers PostgreSQL + Kafka, rather than a 500. It carries
    // NO tenant header on purpose: the tenancy filter's exclude-paths default exempts /actuator/**,
    // so a liveness/readiness probe is not rejected under missing-policy=REJECT.
    mvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void approvingReviewOfAnOrderNotAwaitingReviewRenders409AndCode() throws Exception {
    // SKU-1 needs no manual review, so placing it clears the order for fulfilment immediately
    // (it is already past AWAITING_REVIEW when the POST returns). Approving a review it never
    // required is a conflict with the current state → 409, carrying the domain's stable code.
    String body =
        """
                {"customerId":"CUST-1",
                 "lines":[{"sku":"SKU-1","quantity":1,"unitAmountMinor":100,"currency":"USD"}]}
                """;
    String location =
        mvc.perform(
                post("/orders")
                    .header("X-Tenant-Id", TENANT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getHeader("Location");

    mvc.perform(post(location + "/approve-review").header("X-Tenant-Id", TENANT))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409))
        // Unlike a bare state-machine guard, this conflict carries a stable domain code — the
        // CONFLICT-category error the aggregate raises for a review action on a non-reviewable
        // order.
        .andExpect(jsonPath("$.code").value("ordering.order-not-awaiting-review"));
  }
}
