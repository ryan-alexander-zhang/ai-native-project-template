package com.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.outbox.engine.relay.OutboxRelay;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What happens to a message the relay cannot deliver, and how an operator gets it moving again.
 *
 * <p>"Gave up" has to mean "set aside", not "dropped". The relay moves a spent message into the
 * dead-letter table in the same transaction that abandons it, so it is never both live and dead and
 * never merely gone. The half that was missing from this sample is the other one: someone has to be
 * able to see what is in there and send it again once the cause is fixed.
 *
 * <h2>Staging a failure that is genuinely permanent</h2>
 *
 * <p>The message here carries a {@code type} no {@code IntegrationEvent} on the classpath claims —
 * a producer that rolled out a new event before this deployment learned about it. Routing finds no
 * topic for it, so it falls to the in-process leg, which cannot resolve the type and throws {@code
 * UnknownIntegrationEventException}. The classifier calls that PERMANENT, and permanent means the
 * first failure is the last: retrying cannot teach the application a class it does not have.
 *
 * <h2>Why the relay is driven by hand</h2>
 *
 * <p>{@code relay.enabled=false} removes the schedule, and the test calls {@link OutboxRelay#relay}
 * itself. That is exactly what the lever added in e4d6596 is for: with the scheduler running, the
 * background poll would race the assertions and — because the lock is on the schedule — could
 * silently skip the direct call altogether.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "aipersimmon.ddd.process-manager.effect-relay.enabled=false",
      "aipersimmon.ddd.process-manager.deadline-worker.enabled=false",
      "aipersimmon.ddd.outbox.relay.enabled=false",
    })
@Import(TestInfrastructure.class)
class DeadLetterReplayTest {

  private static final String UNKNOWN_TYPE = "ordering.a-fact-this-deployment-does-not-know";

  private final TestRestTemplate http;
  private final JdbcTemplate jdbc;
  private final OutboxRelay relay;

  DeadLetterReplayTest(
      @Autowired TestRestTemplate http,
      @Autowired JdbcTemplate jdbc,
      @Autowired OutboxRelay relay) {
    this.http = http;
    this.jdbc = jdbc;
    this.relay = relay;
  }

  @Test
  void anUndeliverableMessageIsSetAsideAndCanBeSentAgain() {
    String eventId = writeUndeliverableOutboxRow();

    relay.relay();

    // Set aside, not dropped, and not left in the hot table as a tombstone either.
    assertEquals(0, outboxRows(eventId), "a spent message must leave the outbox");
    assertEquals(1, deadLetterRows(eventId), "and must arrive in the dead-letter table");

    JsonNode listed = deadLetterFor(eventId);
    assertNotNull(listed, "an operator must be able to find it without reading the schema");
    assertEquals("PERMANENT", listed.path("reason").asText());
    assertEquals(
        1,
        listed.path("attempts").asInt(),
        "a permanent failure spends one attempt, not the budget");
    assertTrue(
        listed.path("lastError").asText().contains(UNKNOWN_TYPE),
        "the recorded error must name what could not be delivered");

    // The same row is reachable by id, for the operator who arrives from an alert instead of a
    // list.
    JsonNode fetched =
        http.exchange("/ops/dead-letters/" + eventId, HttpMethod.GET, empty(), JsonNode.class)
            .getBody();
    assertEquals("PERMANENT", fetched.path("reason").asText());

    // The operator fixes the cause — here, nothing to fix, the point is the requeue — and replays.
    ResponseEntity<Void> replayed =
        http.exchange(
            "/ops/dead-letters/" + eventId + "/replay", HttpMethod.POST, empty(), Void.class);
    assertEquals(204, replayed.getStatusCode().value());

    assertEquals(0, deadLetterRows(eventId), "a replayed message leaves the dead-letter table");
    assertEquals(1, outboxRows(eventId), "and returns to the outbox");
    assertEquals(
        0,
        jdbc.queryForObject(
            "select attempts from aipersimmon_outbox where event_id = ?", Integer.class, eventId),
        "its delivery bookkeeping is reset, so the retry budget starts over");
    assertEquals(
        Boolean.FALSE,
        jdbc.queryForObject(
            "select sent from aipersimmon_outbox where event_id = ?", Boolean.class, eventId),
        "and it is unsent, so the next poll picks it up");
    // The id is the original one: a consumer that saw this message before delivery broke will
    // recognise the replay through the inbox rather than processing it twice.
    assertEquals(
        eventId,
        jdbc.queryForObject(
            "select event_id from aipersimmon_outbox where event_id = ?", String.class, eventId));
  }

  @Test
  void replayingSomethingThatIsNotThereIs404() {
    ResponseEntity<Void> response =
        http.exchange(
            "/ops/dead-letters/" + UUID.randomUUID() + "/replay",
            HttpMethod.POST,
            empty(),
            Void.class);

    assertEquals(404, response.getStatusCode().value());
  }

  /**
   * An outbox row whose type nothing on the classpath can resolve. Written directly because the
   * point is a message this deployment cannot understand — there is, by definition, no application
   * code that produces one.
   */
  private String writeUndeliverableOutboxRow() {
    String eventId = UUID.randomUUID().toString();
    Timestamp now = Timestamp.from(Instant.now());
    jdbc.update(
        "INSERT INTO aipersimmon_outbox"
            + " (event_id, source, type, version, payload, occurred_at, subject,"
            + "  correlation_id, sent, attempts, created_at, tenant_id)"
            + " VALUES (?, 'ordering', ?, 1, '{}', ?, 'order-x', ?, false, 0, ?, '__root__')",
        eventId,
        UNKNOWN_TYPE,
        now,
        eventId,
        now);
    return eventId;
  }

  /**
   * Found the way an operator would find it: page the listing, no id known in advance. The rows
   * come from the {@code DeadLetters} port, so this walks the framework's own read side rather than
   * a query the sample wrote against a table it does not own.
   */
  private JsonNode deadLetterFor(String eventId) {
    JsonNode page =
        http.exchange("/ops/dead-letters?size=50", HttpMethod.GET, empty(), JsonNode.class)
            .getBody();
    for (JsonNode row : page.path("items")) {
      if (eventId.equals(row.path("eventId").asText())) {
        return row;
      }
    }
    return null;
  }

  private int outboxRows(String eventId) {
    return count("aipersimmon_outbox", eventId);
  }

  private int deadLetterRows(String eventId) {
    return count("aipersimmon_dead_letter", eventId);
  }

  private int count(String table, String eventId) {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from " + table + " where event_id = ?", Integer.class, eventId);
    return count == null ? 0 : count;
  }

  /** The ops endpoints carry no tenant: a dead letter belongs to the deployment, not a tenant. */
  private static HttpEntity<Void> empty() {
    return new HttpEntity<>(new HttpHeaders());
  }
}
