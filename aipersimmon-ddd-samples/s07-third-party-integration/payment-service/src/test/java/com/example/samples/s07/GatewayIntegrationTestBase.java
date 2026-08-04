package com.example.samples.s07;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipersimmon.ddd.outbox.engine.relay.OutboxRelay;
import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import com.example.thirdparty.paygate.CallbackSigner;
import com.example.thirdparty.paygate.FakePaymentGateway;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * One PostgreSQL container and one fake provider, shared by every test that needs both.
 *
 * <p>The property set lives here rather than on each subclass, verbatim and identical, so all of them
 * share a single Spring context. A subclass that adds one property gets a second container's worth of
 * startup and a second application — which is fine when it is the point (see {@code ScheduleTest}) and
 * pure waste otherwise.
 *
 * <p>Three of the four properties turn schedules off, because a test that asserts on a background
 * schedule is asserting on a race. The relay and the reconciler are driven by hand instead, which is the
 * use the library documents for {@code relay.enabled=false}. The fourth shortens the retry backoff, so a
 * test can watch a second attempt without waiting a second for it.
 *
 * <p>The provider is started once, statically, and never stopped: its threads are daemons and the JVM
 * exit is the cleanup. It <em>is</em> reset before every test — a shared stub carries state between tests
 * as surely as a database does, which S6 learned the expensive way.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "aipersimmon.ddd.outbox.relay.enabled=false",
      "aipersimmon.ddd.outbox.retry.base-backoff-ms=1",
      "payments.reconciliation.enabled=false",
      "payments.reconciliation.stale-after=PT0S"
    })
@Import({PostgresServiceConnection.class, LocalNotes.class})
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
abstract class GatewayIntegrationTestBase {

  /** The same value as {@code payments.gateway.callback-secret}; both sides were given it out of band. */
  protected static final String SECRET = "s07-gateway-secret";

  protected static final FakePaymentGateway gateway = FakePaymentGateway.start(SECRET);

  /**
   * The provider's address is only known once it has bound a port, which is after the application
   * context's properties would normally be fixed. A {@code @DynamicPropertySource} is how a test tells
   * the context something the test itself decided.
   */
  @DynamicPropertySource
  static void gatewayLocation(DynamicPropertyRegistry registry) {
    registry.add("payments.gateway.base-url", gateway::baseUrl);
  }

  @Autowired protected TestRestTemplate http;
  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected OutboxRelay relay;

  @LocalServerPort protected int port;

  @BeforeEach
  void resetTheWorld() {
    gateway.reset();
    gateway.callbacksTo("http://localhost:" + port + "/gateway-callbacks/charges");
    jdbc.update("DELETE FROM aipersimmon_outbox");
    jdbc.update("DELETE FROM aipersimmon_dead_letter");
    jdbc.update("DELETE FROM aipersimmon_web_nonce");
    jdbc.update("DELETE FROM s07_payment");
  }

  // ------------------------------------------------------------------ driving the service

  /** Requests a payment through the HTTP edge and returns its id. */
  protected String requestPayment(String orderRef, long amountMinor) {
    ResponseEntity<Map> response =
        http.postForEntity(
            "/payments", Map.of("orderRef", orderRef, "amountMinor", amountMinor), Map.class);
    assertThat(response.getStatusCode().value())
        .as("requesting a payment should be accepted, body was %s", response.getBody())
        .isEqualTo(202);
    return (String) response.getBody().get("id");
  }

  /** A fresh order reference, so the uniqueness index cannot make two tests interfere. */
  protected String someOrderRef() {
    return "order-" + UUID.randomUUID();
  }

  // ------------------------------------------------------------------ playing the provider

  /**
   * The provider's notification body. Written out here rather than built from a record, because the
   * whole point of the callback tests is what arrives on the wire.
   */
  protected String notificationBody(String paymentId, String txnRef, String resultCode) {
    return "{\"event_id\":\"evt_"
        + UUID.randomUUID()
        + "\",\"txn_ref\":\""
        + txnRef
        + "\",\"merchant_ref\":\""
        + paymentId
        + "\",\"result_code\":\""
        + resultCode
        + "\",\"result_desc\":\"whatever the provider felt like writing\",\"notified_at\":\""
        + Instant.now()
        + "\"}";
  }

  /** Posts a correctly signed notification, the way the provider would. */
  protected ResponseEntity<String> notify(String body) {
    return notify(body, Instant.now().getEpochSecond(), "nonce-" + UUID.randomUUID(), SECRET);
  }

  /**
   * Posts a notification with every part of the signing scheme under the caller's control, so a test can
   * get exactly one of them wrong.
   */
  protected ResponseEntity<String> notify(
      String body, long timestamp, String nonce, String signingSecret) {
    return notifyWithSignature(
        body, timestamp, nonce, CallbackSigner.sign(timestamp, nonce, body, signingSecret));
  }

  protected ResponseEntity<String> notifyWithSignature(
      String body, long timestamp, String nonce, String signature) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (signature != null) {
      headers.add("X-Gateway-Signature", signature);
    }
    headers.add("X-Gateway-Timestamp", Long.toString(timestamp));
    headers.add("X-Gateway-Nonce", nonce);
    return http.exchange(
        "/gateway-callbacks/charges",
        HttpMethod.POST,
        new HttpEntity<>(body, headers),
        String.class);
  }

  // ------------------------------------------------------------------ looking at the result

  protected String statusOf(String paymentId) {
    return single("SELECT status FROM s07_payment WHERE id = ?", String.class, paymentId);
  }

  protected String gatewayRefOf(String paymentId) {
    return single("SELECT gateway_ref FROM s07_payment WHERE id = ?", String.class, paymentId);
  }

  protected String reviewReasonOf(String paymentId) {
    return single("SELECT review_reason FROM s07_payment WHERE id = ?", String.class, paymentId);
  }

  protected long countOf(String table) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
  }

  /**
   * A single value, or null when there is no row.
   *
   * <p>Not {@code queryForObject}: that throws when nothing is there, and an exception thrown inside an
   * Awaitility {@code untilAsserted} block is not an {@code AssertionError}, so it ends the wait instead
   * of being retried. S5 lost an afternoon to that.
   */
  protected <T> T single(String sql, Class<T> type, Object... args) {
    List<T> rows = jdbc.queryForList(sql, type, args);
    return rows.isEmpty() ? null : rows.get(0);
  }
}
