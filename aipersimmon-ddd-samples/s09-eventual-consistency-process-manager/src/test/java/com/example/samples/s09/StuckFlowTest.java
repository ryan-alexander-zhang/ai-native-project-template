package com.example.samples.s09;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipersimmon.ddd.processmanager.engine.operation.ProcessOperations;
import com.aipersimmon.ddd.processmanager.engine.relay.ProcessEffectRelay;
import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The scenario the catalogue asks about and every deployment eventually has: a flow that cannot make
 * progress, and what an operator does about it.
 *
 * <p>The cause here is the most common one in practice — a misconfiguration rather than a bug. An order is
 * placed for a seat class that does not exist, so the first participant throws, and no number of retries
 * can help. Its own context because {@code max-attempts=1} is the thing under test: with the default of
 * twelve, the same story takes an hour of backoff to tell.
 *
 * <p>What the engine does with it is the point: the effect goes DEAD, the instance goes SUSPENDED, and both
 * facts are rows an operator can query. Then the fix is the data, and the resumption is one API call — no
 * state editing, because the library deliberately offers none.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "aipersimmon.ddd.process-manager.effect-relay.poll-delay=1h",
      "aipersimmon.ddd.process-manager.effect-relay.max-attempts=1",
      "aipersimmon.ddd.process-manager.deadline-worker.poll-delay=1h",
      "aipersimmon.ddd.process-manager.parked-input-worker.poll-delay=1h",
      "ticketing.seat-wait=PT1H"
    })
@Import(PostgresServiceConnection.class)
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class StuckFlowTest {

  @Autowired private TestRestTemplate http;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ProcessEffectRelay relay;
  @Autowired private ProcessOperations operations;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM aipersimmon_process_deadline");
    jdbc.update("DELETE FROM aipersimmon_process_effect");
    jdbc.update("DELETE FROM aipersimmon_process_transition");
    jdbc.update("DELETE FROM aipersimmon_process_instance");
    jdbc.update("DELETE FROM s09_wallet_entry");
    jdbc.update("DELETE FROM s09_seat_hold");
    jdbc.update("DELETE FROM s09_ticket_order");
    jdbc.update("DELETE FROM s09_seat_class WHERE seat_class = 'GALLERY'");
    jdbc.update(
        "UPDATE s09_wallet SET balance_minor = 20000, version = version + 1 WHERE customer_id = 'customer-1'");
  }

  @Test
  void aflowStuckOnAMisconfigurationSuspendsItselfAndAnOperatorCanResumeIt() {
    Map<?, ?> created =
        http.postForObject(
            "/orders",
            Map.of("customerId", "customer-1", "seatClass", "GALLERY", "amountMinor", 4500),
            Map.class);
    String orderId = (String) created.get("id");

    // One attempt, one failure, and the engine gives up on this effect rather than hammering a request that
    // cannot succeed.
    relay.pollOnce();

    assertThat(effectStatus()).isEqualTo("DEAD");
    assertThat(lifecycle(orderId)).isEqualTo("SUSPENDED");

    // Not silence — but the diagnosis takes two rows, which is worth knowing before an incident. The
    // instance says WHICH work is stuck; the failing effect's own row says WHY.
    Map<String, Object> flow = http.getForObject("/flows/" + orderId, Map.class);
    assertThat((String) flow.get("suspensionReason"))
        .contains("exhausted retries")
        .contains(effectId());
    assertThat(flow).containsEntry("step", "AWAITING_SEAT");
    assertThat(single("SELECT last_error FROM aipersimmon_process_effect", String.class))
        .contains("GALLERY");
    // And the second row is reachable only by SQL: ProcessQuery exposes the suspension reason and not the
    // failing effect's error, so an operator screen cannot be built from the library's ports alone. Filed
    // as issue-00164.

    // The fix is the data, not the flow's state. There is deliberately no setState or forceStep in the
    // library: a coordinator whose state can be hand-edited is one whose invariants are whatever the last
    // operator believed.
    jdbc.update("INSERT INTO s09_seat_class (seat_class, available) VALUES ('GALLERY', 5)");
    operations.redriveEffect(effectId(), "ops", "seat class was missing; created it");

    assertThat(lifecycle(orderId)).as("redriving the dead work resumes the instance").isEqualTo("RUNNING");

    for (int round = 0; round < 20 && relay.pollOnce() > 0; round++) {
      // deliver whatever the resumed flow stages next
    }

    // And the flow finishes as if the misconfiguration had never happened — which is the whole promise of a
    // durable coordinator: the order did not need to be replaced, re-placed, or reconciled by hand.
    assertThat(status(orderId)).isEqualTo("TICKETED");
    assertThat(lifecycle(orderId)).isEqualTo("COMPLETED");
    assertThat(single("SELECT available FROM s09_seat_class WHERE seat_class = 'GALLERY'", Integer.class))
        .isEqualTo(4);
  }

  private String effectStatus() {
    return single("SELECT status FROM aipersimmon_process_effect", String.class);
  }

  private String effectId() {
    return single("SELECT effect_id FROM aipersimmon_process_effect", String.class);
  }

  private String lifecycle(String orderId) {
    return single(
        "SELECT lifecycle FROM aipersimmon_process_instance WHERE business_key = ?",
        String.class,
        orderId);
  }

  private String status(String orderId) {
    return single("SELECT status FROM s09_ticket_order WHERE id = ?", String.class, orderId);
  }

  private <T> T single(String sql, Class<T> type, Object... args) {
    List<T> rows = jdbc.queryForList(sql, type, args);
    return rows.isEmpty() ? null : rows.get(0);
  }
}
