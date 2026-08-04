package com.example.samples.s22.ordering;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipersimmon.ddd.outbox.engine.relay.OutboxRelay;
import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
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
 * The operator's half: finding a give-up, reading it, and requeueing it — through the service's own
 * endpoints, with the cause still broken.
 *
 * <p>The topic is still missing in this class, deliberately. A runbook has to be exercised in the
 * state an incident is actually in, and "replay before the fix" is the button everyone presses first.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "aipersimmon.ddd.outbox.relay.enabled=false",
      "ordering.events-topic=s22.ordering.never-provisioned"
    })
@Import({PostgresServiceConnection.class, StrictKafka.class})
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class ReplayRunbookTest {

  @Autowired private TestRestTemplate http;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OutboxRelay relay;

  @BeforeEach
  void reset() {
    Outbox.clear(jdbc);
  }

  /**
   * The listing exists, and that is the claim.
   *
   * <p>{@code DeadLetterStore.replay} takes an event id the caller must already have, and before the
   * library split out a read port the only place to get one was a hand-written query against a table
   * the application does not own. A service that ships an outbox and no listing has quarantined its
   * messages into a room with no door: the messages are safe, and nobody can reach them.
   *
   * <p>What is returned is triage, not the message: reason, attempts, subject, when. No payload — see
   * the controller for why that is the right refusal.
   */
  @Test
  void thegiveUpIsFoundThroughTheServiceRatherThanThroughPsql() {
    Outbox.writeLeftoverRow(jdbc, "left-over-1", "order-1");
    relay.relay();

    String listing = get("/ops/dead-letters?size=20");
    assertThat(JsonPath.<List<Object>>read(listing, "$.items")).hasSize(1);
    assertThat(JsonPath.<String>read(listing, "$.items[0].eventId")).isEqualTo("left-over-1");
    assertThat(JsonPath.<String>read(listing, "$.items[0].reason")).isEqualTo("PERMANENT");
    assertThat(JsonPath.<Integer>read(listing, "$.items[0].attempts")).isEqualTo(1);
    assertThat(JsonPath.<String>read(listing, "$.items[0].subject")).isEqualTo("order-1");

    // And by id, for an operator who arrived from an alert with nothing but an event id.
    String one = get("/ops/dead-letters/left-over-1");
    assertThat(JsonPath.<String>read(one, "$.type"))
        .isEqualTo("com.example.samples.ordering.OrderRetired");
  }

  /**
   * Paging is by opaque cursor, the same shape as any other read model (S20).
   *
   * <p>Worth one test because an operations listing is where offset paging is most tempting and worst:
   * the table is ordered by failure time and grows at the head, so page 2 of an offset listing during
   * an incident shows rows that page 1 already showed. A cursor is a position, not a count.
   */
  @Test
  void thelistingPagesByCursorAndTheCursorIsOpaque() {
    Outbox.writeLeftoverRow(jdbc, "left-over-1", "order-1");
    Outbox.writeLeftoverRow(jdbc, "left-over-2", "order-2");
    Outbox.writeLeftoverRow(jdbc, "left-over-3", "order-3");
    relay.relay();
    assertThat(Outbox.deadCount(jdbc)).isEqualTo(3);

    String first = get("/ops/dead-letters?size=2");
    assertThat(JsonPath.<List<Object>>read(first, "$.items")).hasSize(2);
    String cursor = JsonPath.read(first, "$.nextCursor");
    assertThat(cursor).isNotBlank();

    String second = get("/ops/dead-letters?size=2&after=" + cursor);
    assertThat(JsonPath.<List<Object>>read(second, "$.items")).hasSize(1);
    // Exhausted, so no further cursor — the caller stops because the server said so, not because it
    // counted.
    assertThat(JsonPath.<String>read(second, "$.nextCursor")).isNull();
  }

  /**
   * Replay before fixing the cause spends the attempts again and comes back. That is the design, and
   * it is the reason replay can be a button rather than a change-controlled procedure.
   *
   * <p>Nothing in the library or in this service can verify that the underlying cause is gone — no code
   * can. So the honest arrangement is to make a wrong replay <em>cheap</em> instead of impossible: the
   * message goes back to the outbox unsent with its attempt count reset, fails the same way, and
   * returns to the same table. An operator loses a few attempts and learns something; nobody has to
   * gate the endpoint behind a promise it cannot check.
   */
  @Test
  void replayingBeforeTheFixJustSpendsTheAttemptsAgain() {
    Outbox.writeLeftoverRow(jdbc, "left-over-1", "order-1");
    relay.relay();
    assertThat(Outbox.deadCount(jdbc)).isEqualTo(1);

    ResponseEntity<String> replayed = http.postForEntity("/ops/dead-letters/left-over-1/replay", null, String.class);

    assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    // Back in the outbox, unsent, attempts reset — and out of the dead-letter table, so a listing does
    // not show a message that is live again.
    assertThat(Outbox.unsentCount(jdbc)).isEqualTo(1);
    assertThat(Outbox.deadCount(jdbc)).isZero();
    assertThat(jdbc.queryForObject("SELECT attempts FROM aipersimmon_outbox", Integer.class))
        .isZero();

    relay.relay();

    assertThat(Outbox.deadCount(jdbc)).isEqualTo(1);
    assertThat(Outbox.liveCount(jdbc)).isZero();
  }

  /**
   * Pressing replay twice is a 404, not a duplicate.
   *
   * <p>Idempotent by consequence rather than by a guard: the first replay moved the row out of the
   * dead-letter table, so the second finds nothing to move. Worth asserting because the failure mode it
   * rules out is the one an anxious operator produces — refreshing, pressing again, not sure whether
   * the first one went through — and a second requeue would put the same event in the outbox twice.
   */
  @Test
  void pressingReplayTwiceRequeuesNothingTheSecondTime() {
    Outbox.writeLeftoverRow(jdbc, "left-over-1", "order-1");
    relay.relay();

    assertThat(replay("left-over-1").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(replay("left-over-1").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    assertThat(Outbox.unsentCount(jdbc)).isEqualTo(1);
  }

  /** An id nobody ever heard of is the same answer, for the same reason. */
  @Test
  void replayingAnUnknownIdIsNotFound() {
    assertThat(replay("never-existed").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  private ResponseEntity<String> replay(String eventId) {
    return http.postForEntity("/ops/dead-letters/" + eventId + "/replay", null, String.class);
  }

  private String get(String path) {
    ResponseEntity<String> response = http.getForEntity(path, String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return response.getBody();
  }
}
