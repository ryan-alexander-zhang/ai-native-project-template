package com.example.samples.s22.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aipersimmon.ddd.outbox.engine.relay.OutboxRelay;
import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import java.time.Duration;
import java.util.Map;
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

/**
 * How a message stops being live, with a real cause and a real broker.
 *
 * <p>The cause is a destination topic that was never provisioned — {@code ordering.events-topic} points
 * somewhere nothing created, and {@link StrictKafka} has auto-creation off. That is the most common way
 * a healthy service publishes into nothing: a topic renamed in one environment, a terraform apply that
 * did not run, a new event whose topic nobody added. Nothing is stubbed, and no failure is injected;
 * the failure is the configuration.
 *
 * <p>The relay's schedule is off so each test drives its own polls and asserts on exactly what they did.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "aipersimmon.ddd.outbox.relay.enabled=false",
      "ordering.events-topic=s22.ordering.never-provisioned"
    })
@Import({PostgresServiceConnection.class, StrictKafka.class})
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class DeadLetterTest {

  @Autowired private TestRestTemplate http;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OutboxRelay relay;

  @BeforeEach
  void reset() {
    Outbox.clear(jdbc);
  }

  /**
   * The caller is told the order was placed, because it was. Delivery has not happened and cannot be
   * reported to anyone who is still waiting.
   *
   * <p>This is the asymmetry every operational tool in this sample exists to answer. A synchronous
   * design fails in front of the person who caused it; an asynchronous one fails on a scheduler
   * thread, hours later, with nobody in the room. The 201 is not a bug — it is the correct answer,
   * and it is also the reason a dead-letter table is not optional decoration.
   */
  @Test
  void themissingTopicIsInvisibleToTheCaller() {
    ResponseEntity<String> response = place("customer-1", "sku-keyboard", 2);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(count("s22_order")).isEqualTo(1);
    assertThat(Outbox.unsentCount(jdbc)).isEqualTo(1);
    assertThat(Outbox.deadCount(jdbc)).isZero();
  }

  /**
   * Three attempts, then set aside — and the row <em>moves</em>.
   *
   * <p>Moving rather than flagging is the design decision worth noticing. A spent message left in the
   * outbox with a "gave up" column would either keep being selected (a poll re-attempting a hopeless
   * row every second) or become an unselectable tombstone in the table the business transaction writes
   * to on every command. Both are paid for by the hot path. Here the outbox holds only live work, and
   * the give-up lives in a table that only an operator reads.
   */
  @Test
  void therelaySpendsItsAttemptsAndThenMovesTheRowAside() {
    place("customer-1", "sku-keyboard", 2);

    pollUntilOneIsSetAside();

    Map<String, Object> dead = Outbox.deadRows(jdbc).get(0);
    assertThat(dead.get("reason")).isEqualTo("RETRIES_EXHAUSTED");
    // Exactly the ceiling, not one more and not one less. The count is what tells an operator whether
    // the message was tried at all.
    assertThat(dead.get("attempts")).isEqualTo(3);
    assertThat(dead.get("type")).isEqualTo("com.example.samples.ordering.OrderPlaced");
    // The destination travels with the give-up, because a replay has to go where the row was
    // addressed. Without it a replayed externalized event would come back as in-process — the same
    // silent loss through a second door.
    assertThat(dead.get("destination")).isEqualTo("s22.ordering.never-provisioned");
    // And what the operator actually gets: the whole cause chain, not just the wrapper.
    //
    // These two lines are the record of a library fix, and they used to read the other way round. The
    // relay recorded only the outermost exception's class and message — which for the commonest publish
    // failure of all is Spring Kafka's content-free "Send failed", with the topic name and the reason
    // ("not present in metadata", UnknownTopicOrPartitionException) discarded two levels down the chain.
    // That was measured here, filed as issue-00165, and asserted *as it was* rather than as it ought to
    // be, precisely so that fixing it would break the test. It is fixed (FailureSummary flattens the
    // chain), so last_error now answers "where was this going and why did it not get there".
    String lastError = (String) dead.get("last_error");
    assertThat(lastError).contains("Send failed");
    assertThat(lastError).contains("never-provisioned");
    assertThat(lastError).contains("not present in metadata");
    // Moved, not copied: the hot table is empty again.
    assertThat(Outbox.liveCount(jdbc)).isZero();
  }

  /**
   * A permanent failure spends one attempt, not three.
   *
   * <p>The row is a leftover from a deploy that retired an event class (see {@link
   * Outbox#writeLeftoverRow}). No number of retries conjures a class, so the classifier calls it
   * permanent and the relay stops immediately. That difference is the whole reason {@code reason} is a
   * column: {@code RETRIES_EXHAUSTED} says the environment was probably at fault and a replay is worth
   * pressing; {@code PERMANENT} says the message itself cannot be delivered as it stands, and pressing
   * replay before changing something is a no-op with extra logs.
   */
  @Test
  void apermanentFailureIsSetAsideWithoutSpendingRetries() {
    Outbox.writeLeftoverRow(jdbc, "left-over-1", "order-gone");

    relay.relay();

    Map<String, Object> dead = Outbox.deadRows(jdbc).get(0);
    assertThat(dead.get("reason")).isEqualTo("PERMANENT");
    assertThat(dead.get("attempts")).isEqualTo(1);
    assertThat((String) dead.get("last_error")).contains("UnknownIntegrationEventException");
    assertThat(Outbox.liveCount(jdbc)).isZero();
  }

  /**
   * Giving up on a message unblocks its aggregate — and that is a consequence to understand, not a
   * feature to celebrate.
   *
   * <p>Only the head of a subject's queue is claimable, which is what keeps one aggregate's events in
   * order. A row that is retired leaves the table, so its successor becomes the head and ships. The
   * ordering guarantee therefore holds <em>up to the point a message is given up on</em>, and after
   * that the downstream sees event 2 of an aggregate without event 1.
   *
   * <p>The alternative — leaving the queue stuck behind the poison — trades a gap for a stall, and a
   * stall on one aggregate is invisible while the rest of the traffic flows. Neither is free. The
   * operational answer is that dead letters need an alert with a low threshold, because "one message
   * was set aside" and "a consumer's view of that aggregate is now wrong" are the same event.
   */
  @Test
  void agiveUpDoesNotHoldBackTheAggregatesNextEvent() {
    Outbox.writeLeftoverRow(jdbc, "left-over-1", "order-77");
    Outbox.writeLeftoverRow(jdbc, "left-over-2", "order-77");

    relay.relay();

    assertThat(Outbox.deadCount(jdbc)).isEqualTo(2);
    assertThat(Outbox.liveCount(jdbc)).isZero();
  }

  /** Drives polls until the message has been set aside, which takes three failures and two backoffs. */
  private void pollUntilOneIsSetAside() {
    await()
        .atMost(Duration.ofSeconds(90))
        .pollInterval(Duration.ofMillis(100))
        .until(
            () -> {
              relay.relay();
              return Outbox.deadCount(jdbc) == 1;
            });
  }

  private ResponseEntity<String> place(String customerId, String sku, int quantity) {
    return http.postForEntity(
        "/orders",
        Map.of("customerId", customerId, "sku", sku, "quantity", quantity),
        String.class);
  }

  private long count(String table) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
  }
}
