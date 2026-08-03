package com.example.samples.s06;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipersimmon.ddd.cqrs.QueryBus;
import com.example.samples.s06.risk.application.AssessRisk;
import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The callee's contract as a caller sees it — and the consequences of owning no database.
 *
 * <p>No Testcontainers here, and that is the point rather than a shortcut: there is nothing to contain.
 * A decision service's tests are as fast as its dependencies are few.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RiskApiTest {

  @Autowired private TestRestTemplate http;
  @Autowired private QueryBus queryBus;

  @Test
  void anacceptableOrderIsApproved() {
    ResponseEntity<String> response = assess("customer-1", 5_000);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(JsonPath.<Boolean>read(response.getBody(), "$.approved")).isTrue();
  }

  @Test
  void arejectionIsATwoHundredAndNotAClientError() {
    ResponseEntity<String> response = assess("customer-1", 500_000);

    // The decision this service is built around. "Assessed, and the answer is no" is a successful
    // assessment: the request was perfectly good. Returning 422 here would leave the caller unable to
    // tell a business refusal from its own broken payload — one is a message for the customer, the other
    // is a page for an engineer.
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(JsonPath.<Boolean>read(response.getBody(), "$.approved")).isFalse();
    assertThat(JsonPath.<String>read(response.getBody(), "$.reason")).contains("exceeds");
  }

  @Test
  void ablockedCustomerIsRejectedWithItsOwnReason() {
    ResponseEntity<String> response = assess("customer-blocked", 100);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(JsonPath.<Boolean>read(response.getBody(), "$.approved")).isFalse();
    assertThat(JsonPath.<String>read(response.getBody(), "$.reason")).contains("blocked");
  }

  @Test
  void amalformedRequestIsAProblemDocumentAndNotADecision() {
    ResponseEntity<String> response = assess("", 5_000);

    // The other kind of "no", and it looks entirely different: a 4xx carrying the framework's RFC 9457
    // problem body, with a machine-readable code and the request id. The controller writes no error
    // handling to get this — it is what the web starter does with a failed @Valid.
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).contains("\"code\"");
    assertThat(response.getBody()).doesNotContain("approved");
  }

  @Test
  void thequerySideRunsWithNoTransactionAtAllAndNothingPretendsOtherwise() {
    // A database-less service has no transaction manager, so the starter's UnitOfWork and transaction
    // interceptor are both absent. What makes that safe rather than silent is that the starter refuses to
    // start unless the deployment declares it (aipersimmon.ddd.cqrs.transaction.required=false, which
    // this service sets and which WARNs on every boot).
    //
    // And on the read side there was never a transaction to lose: the framework ships zero query
    // interceptors, so ask() goes straight to the handler.
    boolean inTransaction =
        Boolean.TRUE.equals(
            queryBus.ask(new AssessRisk("customer-1", 1)) != null
                ? TransactionSynchronizationManager.isActualTransactionActive()
                : null);

    assertThat(inTransaction).isFalse();
  }

  private ResponseEntity<String> assess(String customerId, long amountCents) {
    return http.postForEntity(
        "/risk-assessments",
        Map.of("customerId", customerId, "amountCents", amountCents),
        String.class);
  }
}
