package com.example.samples.s07;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import com.example.thirdparty.paygate.FakePaymentGateway;
import com.example.thirdparty.paygate.GatewayMode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Both schedules on, nobody driving anything: one POST, and a payment whose callback never arrives still
 * ends up settled.
 *
 * <p>Its own context and its own stub, because the properties under test are exactly the ones every other
 * test here turns off. That is the trigger/work split working as intended rather than a workaround — the
 * relay and the reconciliation round are ordinary methods, so eleven tests drive them directly and this one
 * proves the timers are really wired to them.
 *
 * <p>It is also the only test that exercises the arrangement a deployment actually runs, which is worth one
 * slow test: the pieces can each be right and the wiring still be missing a property.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "aipersimmon.ddd.outbox.poll-delay-ms=100",
      "payments.reconciliation.enabled=true",
      "payments.reconciliation.poll-delay-ms=100",
      "payments.reconciliation.stale-after=PT0S"
    })
@Import(PostgresServiceConnection.class)
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class UnattendedFlowTest {

  private static final FakePaymentGateway silentGateway =
      FakePaymentGateway.start("s07-gateway-secret");

  @DynamicPropertySource
  static void gatewayLocation(DynamicPropertyRegistry registry) {
    registry.add("payments.gateway.base-url", silentGateway::baseUrl);
  }

  @Autowired private TestRestTemplate http;
  @Autowired private JdbcTemplate jdbc;

  @LocalServerPort private int port;

  @Test
  void apaymentIsChargedAndSettledWithNobodyDrivingEitherStep() {
    jdbc.update("DELETE FROM s07_payment");
    silentGateway.reset();
    // The provider charges and never calls back — the one failure that no retry fixes. The callback URL is
    // configured anyway, so the test is not passing because we forgot to give it one.
    silentGateway.mode(GatewayMode.SILENT);
    silentGateway.callbacksTo("http://localhost:" + port + "/gateway-callbacks/charges");

    ResponseEntity<Map> accepted =
        http.postForEntity(
            "/payments",
            Map.of("orderRef", "order-unattended", "amountMinor", 4500),
            Map.class);
    assertThat(accepted.getStatusCode().value()).isEqualTo(202);
    String paymentId = (String) accepted.getBody().get("id");

    // The relay's timer sends the charge; the reconciler's timer asks about it and settles it. Neither is
    // touched by this test.
    await()
        .atMost(Duration.ofSeconds(20))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> assertThat(statusOf(paymentId)).isEqualTo("SUCCEEDED"));

    assertThat(silentGateway.chargesCreated()).isEqualTo(1);
    assertThat(silentGateway.callbackResponses()).as("the provider said nothing").isEmpty();
  }

  private String statusOf(String paymentId) {
    List<String> rows =
        jdbc.queryForList("SELECT status FROM s07_payment WHERE id = ?", String.class, paymentId);
    return rows.isEmpty() ? null : rows.get(0);
  }
}
