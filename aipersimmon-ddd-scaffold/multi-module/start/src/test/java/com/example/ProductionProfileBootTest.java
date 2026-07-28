package com.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.testsupport.SharedContainers;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The prod profile is a real, startable configuration — not a file nobody has ever run.
 *
 * <p>Before the profile split there was one {@code application.yml} full of development values and
 * <em>no {@code spring.datasource} at all</em>: the only way this application could obtain a
 * database was for Spring Boot's docker-compose support to derive one from a running compose stack,
 * or for a test to hand it one. There was no path to starting it anywhere else (issue-00074). This
 * test is the assertion that the path now exists.
 *
 * <p>It is deliberately wired the awkward way round. The PostgreSQL container comes from {@link
 * SharedContainers} — the raw, non-Spring-managed one — and its coordinates are injected under the
 * names the prod profile actually reads, {@code DB_URL} / {@code DB_USER} / {@code DB_PASSWORD}. A
 * {@code @ServiceConnection} would have been less code and would have proved nothing: it supplies
 * the DataSource itself, so the {@code ${DB_URL}} placeholder would never be resolved and the test
 * would pass with the prod file empty.
 *
 * <p>Kafka is absent on purpose rather than by oversight. The broker hop is covered end to end by
 * the other acceptance tests; what is under test here is whether the configuration resolves and the
 * application comes up, so the relays and the consumer bridge are switched off and no second
 * container is started for them (issue-00092 — every distinct context costs a container pair).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      // No broker in this test; the placeholder still has to resolve, which is itself part of what
      // is being checked — a prod file that cannot resolve its own placeholders is not startable.
      "KAFKA_BOOTSTRAP_SERVERS=localhost:9092",
      "aipersimmon.ddd.messaging.kafka.consumer.enabled=false",
      "aipersimmon.ddd.outbox.relay.enabled=false",
      "aipersimmon.ddd.process-manager.effect-relay.enabled=false",
      "aipersimmon.ddd.process-manager.deadline-worker.enabled=false",
    })
@ActiveProfiles("prod")
class ProductionProfileBootTest {

  @DynamicPropertySource
  static void deploymentEnvironment(DynamicPropertyRegistry registry) {
    PostgreSQLContainer<?> postgres = SharedContainers.postgres();
    registry.add("DB_URL", postgres::getJdbcUrl);
    registry.add("DB_USER", postgres::getUsername);
    registry.add("DB_PASSWORD", postgres::getPassword);
  }

  @Autowired DataSource dataSource;

  @Autowired JdbcTemplate jdbc;

  @Autowired TestRestTemplate http;

  @Test
  void theDatabaseComesFromTheEnvironmentNotFromDockerCompose() throws Exception {
    // The context started at all, which is most of the point. Beyond that: the connection in use
    // is the one DB_URL named. spring.docker.compose.enabled=false in the prod profile means the
    // compose support contributed nothing, and no @ServiceConnection is imported here.
    try (var connection = dataSource.getConnection()) {
      assertEquals(
          SharedContainers.postgres().getJdbcUrl(),
          connection.getMetaData().getURL(),
          "the prod profile must take its database from DB_URL");
    }
  }

  @Test
  void theDemoSeedIsNotInAProductionDatabase() {
    // issue-00072, end to end: the seed is an afterMigrate callback in db/dev, and the prod
    // profile's spring.flyway.locations lists db/migration alone. The schema is fully migrated —
    // the tables exist and are queryable — and Acme is simply not in it.
    Integer customers =
        jdbc.queryForObject("SELECT count(*) FROM ordering.customers", Integer.class);
    Integer stocks = jdbc.queryForObject("SELECT count(*) FROM inventory.stocks", Integer.class);

    assertEquals(0, customers, "a production database must not be handed a customer named Acme");
    assertEquals(0, stocks, "nor three demo SKUs");
  }

  @Test
  void theContractIsPublishedButTheInteractiveConsoleIsNot() {
    // Two different things, which is why they are two settings: /v3/api-docs is the API's
    // description and stays served; Swagger UI is a write-capable console on the live API, and
    // there is no authentication in front of it (design-00013).
    assertEquals(
        200,
        get("/v3/api-docs").getStatusCode().value(),
        "the published contract is useful in every environment");
    assertEquals(
        404,
        get("/swagger-ui.html").getStatusCode().value(),
        "the interactive UI must not be exposed by the production profile");
  }

  @Test
  void aKeylessPostIsRefusedRatherThanRunUnprotected() {
    // require-key flips to true in prod: dev leaves it false so the reference app can be driven
    // with a plain curl, but an unprotected create is exactly what idempotency exists to prevent.
    // The filter runs ahead of the handler, so this is a 400 regardless of the (empty) database.
    HttpHeaders headers = tenantHeader();
    headers.setContentType(MediaType.APPLICATION_JSON);
    String body =
        """
        {"customerId":"CUST-1",
         "lines":[{"sku":"SKU-1","quantity":1,"unitAmountMinor":100,"currency":"USD"}]}
        """;

    ResponseEntity<String> response =
        http.postForEntity("/orders", new HttpEntity<>(body, headers), String.class);

    assertEquals(
        400,
        response.getStatusCode().value(),
        "a POST with no Idempotency-Key must be refused when require-key is on");
    assertTrue(
        response.getBody() != null && response.getBody().contains("Idempotency-Key"),
        () -> "the refusal should name the missing header, got: " + response.getBody());
  }

  private ResponseEntity<String> get(String path) {
    return http.exchange(path, HttpMethod.GET, new HttpEntity<>(tenantHeader()), String.class);
  }

  /**
   * Tenancy is on with missing-policy=REJECT, and only /actuator/** and /ops/** are excluded — so
   * even a documentation request needs a tenant to get past the filter.
   *
   * <p>Any well-formed tenant will do; nothing here reads a row. Not {@code __root__} though —
   * {@code Tenants.of()} rejects the reserved {@code __} prefix at the edge so a client can never
   * name a framework sentinel, and the request would be a 400 before reaching anything this test is
   * about (issue-00096).
   */
  private static HttpHeaders tenantHeader() {
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Tenant-Id", "demo");
    return headers;
  }
}
