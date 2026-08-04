package com.example.samples.s09;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Both workers on, nobody driving anything: one POST, and a ticket exists a moment later.
 *
 * <p>Its own context, because the properties under test are the ones every other test here turns off. That
 * is the trigger/work split working as intended rather than a workaround — the relay and the deadline
 * worker are ordinary methods, so the other tests drive them directly and this one proves the schedules
 * are really wired to them.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "aipersimmon.ddd.process-manager.effect-relay.poll-delay=100ms",
      "aipersimmon.ddd.process-manager.deadline-worker.poll-delay=100ms"
    })
@Import(PostgresServiceConnection.class)
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class UnattendedFlowTest {

  @Autowired private TestRestTemplate http;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void anorderPlacedIsTicketedWithNobodyDrivingAnyStep() {
    jdbc.update("DELETE FROM s09_wallet_entry");
    jdbc.update("DELETE FROM s09_seat_hold");
    jdbc.update("DELETE FROM s09_ticket_order");
    jdbc.update("UPDATE s09_seat_class SET available = 2, version = version + 1 WHERE seat_class = 'STALLS'");
    jdbc.update("UPDATE s09_wallet SET balance_minor = 20000, version = version + 1 WHERE customer_id = 'customer-1'");

    Map<?, ?> created =
        http.postForObject(
            "/orders",
            Map.of("customerId", "customer-1", "seatClass", "STALLS", "amountMinor", 4500),
            Map.class);
    String orderId = (String) created.get("id");

    // Three participants, three transitions, two workers, and no test code in between.
    await()
        .atMost(Duration.ofSeconds(20))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> assertThat(status(orderId)).isEqualTo("TICKETED"));

    assertThat(single("SELECT available FROM s09_seat_class WHERE seat_class = 'STALLS'", Integer.class))
        .isEqualTo(1);
    assertThat(single("SELECT balance_minor FROM s09_wallet WHERE customer_id = 'customer-1'", Long.class))
        .isEqualTo(15500);
  }

  private String status(String orderId) {
    return single("SELECT status FROM s09_ticket_order WHERE id = ?", String.class, orderId);
  }

  private <T> T single(String sql, Class<T> type, Object... args) {
    List<T> rows = jdbc.queryForList(sql, type, args);
    return rows.isEmpty() ? null : rows.get(0);
  }
}
