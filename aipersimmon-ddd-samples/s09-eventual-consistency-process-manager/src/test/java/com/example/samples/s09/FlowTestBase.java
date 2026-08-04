package com.example.samples.s09;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipersimmon.ddd.processmanager.engine.deadline.ProcessDeadlineWorker;
import com.aipersimmon.ddd.processmanager.engine.relay.ProcessEffectRelay;
import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One PostgreSQL container, and both of the coordinator's workers switched off.
 *
 * <p>Silencing them is what makes these tests assertions rather than races. The relay and the deadline
 * worker are ordinary methods ({@code pollOnce}), so a test can say "now deliver the next effect" and
 * assert on exactly what that did — including the intermediate states a running deployment passes through
 * in milliseconds. {@code UnattendedFlowTest} is the one that leaves both on and proves the timers are
 * really wired.
 *
 * <p>The seat wait is zero here so a deadline is due the moment it is armed, which is how the timeout paths
 * are tested without waiting. The payment wait stays long, so the two can be triggered independently.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      // NOT `enabled=false`. That property removes the relay and deadline-worker *beans*, so there is
      // nothing left to drive by hand — unlike the outbox, whose `relay.enabled=false` keeps the bean and
      // stops only its schedule, which is the use its javadoc documents. Filed as issue-00163. Until then,
      // a poll delay longer than any test is how a schedule is silenced without losing the worker: the
      // scheduler's initial delay equals its poll delay, so nothing fires.
      "aipersimmon.ddd.process-manager.effect-relay.poll-delay=1h",
      "aipersimmon.ddd.process-manager.deadline-worker.poll-delay=1h",
      "aipersimmon.ddd.process-manager.parked-input-worker.poll-delay=1h",
      "ticketing.seat-wait=PT0S",
      "ticketing.payment-wait=PT1H"
    })
@Import(PostgresServiceConnection.class)
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
abstract class FlowTestBase {

  @Autowired protected TestRestTemplate http;
  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected ProcessEffectRelay relay;
  @Autowired protected ProcessDeadlineWorker deadlines;

  @BeforeEach
  void resetTheWorld() {
    // The coordinator's tables first: they reference nothing of ours, and clearing them last would leave
    // a flow pointing at an order that no longer exists.
    jdbc.update("DELETE FROM aipersimmon_process_deadline");
    jdbc.update("DELETE FROM aipersimmon_process_effect");
    jdbc.update("DELETE FROM aipersimmon_process_transition");
    jdbc.update("DELETE FROM aipersimmon_process_instance");
    jdbc.update("DELETE FROM s09_wallet_entry");
    jdbc.update("DELETE FROM s09_seat_hold");
    jdbc.update("DELETE FROM s09_ticket_order");
    jdbc.update("UPDATE s09_seat_class SET available = 2, version = version + 1 WHERE seat_class = 'STALLS'");
    jdbc.update("UPDATE s09_seat_class SET available = 0, version = version + 1 WHERE seat_class = 'BALCONY'");
    jdbc.update(
        "UPDATE s09_wallet SET balance_minor = 20000, version = version + 1 WHERE customer_id = 'customer-1'");
  }

  // ------------------------------------------------------------------ driving

  /** Places an order and returns its id. Nothing has been coordinated yet when this returns. */
  protected String placeOrder(String seatClass, long amountMinor) {
    ResponseEntityMap response = post("/orders", Map.of("customerId", "customer-1", "seatClass", seatClass, "amountMinor", amountMinor));
    assertThat(response.status()).as("placing an order, body was %s", response.body()).isEqualTo(202);
    return (String) response.body().get("id");
  }

  protected void requestCancellation(String orderId, String reason) {
    ResponseEntityMap response = post("/orders/" + orderId + "/cancellation", Map.of("reason", reason));
    assertThat(response.status()).isEqualTo(202);
  }

  /**
   * Delivers whatever effects are due, repeatedly, until nothing is pending.
   *
   * <p>One {@code pollOnce} delivers a batch, and delivering it produces the next transition, which stages
   * the next effect — so a flow needs several polls to finish. Looping to quiescence is what a running
   * deployment does continuously; the bound keeps a broken flow from hanging the test.
   */
  protected void runToQuiescence() {
    for (int round = 0; round < 20; round++) {
      if (relay.pollOnce() == 0) {
        return;
      }
    }
    throw new IllegalStateException("the flow did not settle in 20 relay rounds");
  }

  // ------------------------------------------------------------------ observing

  protected String orderStatus(String orderId) {
    return single("SELECT status FROM s09_ticket_order WHERE id = ?", String.class, orderId);
  }

  protected String cancelReason(String orderId) {
    return single("SELECT cancel_reason FROM s09_ticket_order WHERE id = ?", String.class, orderId);
  }

  protected int seatsAvailable(String seatClass) {
    return single("SELECT available FROM s09_seat_class WHERE seat_class = ?", Integer.class, seatClass);
  }

  protected long balance() {
    return single(
        "SELECT balance_minor FROM s09_wallet WHERE customer_id = 'customer-1'", Long.class);
  }

  /** The ledger, oldest first — the exhibit for what a compensation leaves behind. */
  protected List<Map<String, Object>> ledger() {
    return jdbc.queryForList(
        "SELECT reference, kind, amount_minor, note FROM s09_wallet_entry"
            + " WHERE customer_id = 'customer-1' ORDER BY recorded_at, reference");
  }

  protected String flowStep(String orderId) {
    return single(
        "SELECT business_step FROM aipersimmon_process_instance WHERE business_key = ?", String.class, orderId);
  }

  protected String flowLifecycle(String orderId) {
    return single(
        "SELECT lifecycle FROM aipersimmon_process_instance WHERE business_key = ?",
        String.class,
        orderId);
  }

  protected String flowOutcome(String orderId) {
    return single(
        "SELECT outcome FROM aipersimmon_process_instance WHERE business_key = ?",
        String.class,
        orderId);
  }

  protected long transitionCount(String orderId) {
    return single(
        "SELECT COUNT(*) FROM aipersimmon_process_transition t"
            + " JOIN aipersimmon_process_instance i ON i.instance_id = t.instance_id"
            + " WHERE i.business_key = ?",
        Long.class,
        orderId);
  }

  protected ResponseEntityMap post(String path, Map<String, ?> body) {
    org.springframework.http.ResponseEntity<Map> response =
        http.postForEntity(path, body, Map.class);
    return new ResponseEntityMap(response.getStatusCode().value(), response.getBody());
  }

  protected ResponseEntityMap get(String path) {
    org.springframework.http.ResponseEntity<Map> response = http.getForEntity(path, Map.class);
    return new ResponseEntityMap(response.getStatusCode().value(), response.getBody());
  }

  /** A single value, or null when there is no row — see S5 on why {@code queryForObject} is a trap. */
  protected <T> T single(String sql, Class<T> type, Object... args) {
    List<T> rows = jdbc.queryForList(sql, type, args);
    return rows.isEmpty() ? null : rows.get(0);
  }

  /** The two things a test actually asserts on. */
  protected record ResponseEntityMap(int status, Map<String, Object> body) {}
}
