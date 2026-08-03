package com.example.samples.s06;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The caller, against a real socket that behaves badly on request.
 *
 * <p>The read timeout is shortened to 300ms for the test, which is the only concession to speed: everything
 * else — the client, the retry, the translation, the transaction boundary — is the production path.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"ordering.risk-read-timeout-ms=300", "ordering.risk-connect-timeout-ms=300"})
@Import({PostgresServiceConnection.class, TransactionProbes.class})
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class SynchronousCallTest {

  private static RiskStubServer risk;

  @Autowired private TestRestTemplate http;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private TransactionProbes.Recorder probes;

  @BeforeAll
  static void startStub() {
    risk = RiskStubServer.start();
  }

  @AfterAll
  static void stopStub() {
    risk.stop();
  }

  /** Where the callee is, decided after the stub has bound a port. */
  @DynamicPropertySource
  static void riskUrl(DynamicPropertyRegistry registry) {
    registry.add("ordering.risk-service-url", () -> risk.baseUrl());
  }

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM s06_order");
    probes.clear();
  }

  @Test
  void anapprovedOrderIsPlaced() {
    risk.willRespond(RiskStubServer.Behaviour.approved());

    ResponseEntity<String> response = place("customer-1", 5_000);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(orderCount()).isEqualTo(1);
    assertThat(risk.requestCount()).as("asked exactly once on the happy path").isEqualTo(1);
  }

  @Test
  void theRemoteCallHappensBeforeAnyTransactionExists() {
    risk.willRespond(RiskStubServer.Behaviour.approved());

    place("customer-1", 5_000);

    // The whole point of putting the call in a precheck. The framework runs prechecks between validation
    // (100) and the transaction interceptor (200), so the network wait costs a request thread and NOT a
    // database connection. The same call on the handler's first line would hold a connection for the
    // entire round trip, and one slow dependency would drain the pool.
    assertThat(probes.duringPrecheck.get()).as("no transaction during the precheck").isFalse();
    assertThat(probes.insideHandler.get()).as("a transaction inside the handler").isTrue();
  }

  @Test
  void arejectedOrderIsRefusedAsFourTwentyTwoAndNothingIsWritten() {
    risk.willRespond(RiskStubServer.Behaviour.rejected("amount exceeds the unattended limit"));

    ResponseEntity<String> response = place("customer-1", 500_000);

    // A business refusal: the domain-rule family type, 422, carrying THIS context's code. The callee's
    // status was 200 and its vocabulary never reaches the client.
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(response.getBody()).contains("ordering.risk-rejected");
    assertThat(response.getBody()).contains("amount exceeds the unattended limit");
    // And nothing was written, because the transaction never opened. No rollback, no compensation, no
    // half-order — which is the second dividend of doing the call in a precheck.
    assertThat(orderCount()).isZero();
    assertThat(probes.insideHandler.get()).as("the handler never ran").isNull();
  }

  @Test
  void whenTheCalleeIsBrokenTheOrderFailsAsFiveOhThreeAfterOneRetry() {
    risk.willRespond(RiskStubServer.Behaviour.serverError());

    ResponseEntity<String> response = place("customer-1", 5_000);

    // 503 rather than the UNEXPECTED family's 500, because the category enum has no "dependency is down"
    // member and a 500 would tell the client both that the fault is here and that retrying is pointless.
    // The override is one entry in a ProblemCatalog.
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody()).contains("ordering.risk-unavailable");
    // Asked twice: the call is a query, so repeating it is repeating a question. A state-changing remote
    // call could not be retried this way without an idempotency key from the callee.
    assertThat(risk.requestCount()).isEqualTo(2);
    // Fail closed — no order without an answer. Failing open is a legitimate business choice for some
    // products; it would be a catch block in the precheck with a reason written next to it, never the
    // accident of a swallowed exception.
    assertThat(orderCount()).isZero();
  }

  @Test
  void atimeoutIsAFailureToAnswerAndNotARejection() {
    risk.willRespond(RiskStubServer.Behaviour.slow(Duration.ofSeconds(2)));

    ResponseEntity<String> response = place("customer-1", 5_000);

    // The read timeout fired (300ms against a 2s stub) twice, and the result is "no answer" — not
    // "refused". Conflating the two would tell a customer their order was declined because a server was
    // slow, and would stop anyone retrying it.
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody()).contains("ordering.risk-unavailable");
    assertThat(risk.requestCount()).isEqualTo(2);
    assertThat(orderCount()).isZero();
  }

  @Test
  void acalleeProblemDocumentIsNotAllowedToLookLikeARefusal() {
    risk.willRespond(RiskStubServer.Behaviour.badRequestProblem());

    ResponseEntity<String> response = place("customer-1", 5_000);

    // A 4xx from the callee means THIS service sent something wrong — a defect here, not a decision about
    // the customer. So it becomes "no answer" and a 503, and the callee's own problem code
    // (validation.failed) does not appear in this service's response.
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody()).contains("ordering.risk-unavailable");
    assertThat(response.getBody()).doesNotContain("validation.failed");
    assertThat(orderCount()).isZero();
  }

  @Test
  void atwoHundredThisCallerCannotInterpretIsNotAnApproval() {
    risk.willRespond(RiskStubServer.Behaviour.unintelligible());

    ResponseEntity<String> response = place("customer-1", 5_000);

    // The callee changed its response shape. Defaulting a missing `approved` to false would blame the
    // customer for a schema change; defaulting it to true would place unassessed orders. Neither: it is
    // not an answer. That is why the client DTO holds a boxed Boolean.
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(orderCount()).isZero();
  }

  private ResponseEntity<String> place(String customerId, long amountCents) {
    return http.postForEntity(
        "/orders", Map.of("customerId", customerId, "amountCents", amountCents), String.class);
  }

  private long orderCount() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM s06_order", Long.class);
  }
}
