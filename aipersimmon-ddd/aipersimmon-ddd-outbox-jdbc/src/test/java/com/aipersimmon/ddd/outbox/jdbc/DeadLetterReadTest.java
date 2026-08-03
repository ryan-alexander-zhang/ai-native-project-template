package com.aipersimmon.ddd.outbox.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.cqrs.page.Cursor;
import com.aipersimmon.ddd.cqrs.page.Slice;
import com.aipersimmon.ddd.outbox.DeadLetter;
import com.aipersimmon.ddd.outbox.DeadLetterStore;
import com.aipersimmon.ddd.outbox.DeadLetters;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A dead letter that cannot be found cannot be replayed: {@link DeadLetterStore#replay} takes an
 * event id, and an operator has one only if something gave it to them. {@link DeadLetters} is that
 * something — the read side of the same table — and these tests walk the whole operator path, from
 * "what is in there" to a requeued message, without the application writing a line of SQL against a
 * table it does not own (issue-00066).
 */
@SpringBootTest(
    classes = DeadLetterReadTest.TestApp.class,
    properties = "aipersimmon.ddd.outbox.relay.enabled=false")
class DeadLetterReadTest {

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class TestApp {}

  @Autowired DeadLetterStore store;
  @Autowired DeadLetters deadLetters;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM aipersimmon_outbox");
    jdbc.update("DELETE FROM aipersimmon_dead_letter");
  }

  @Test
  void anOperatorArrivesKnowingNothingAndLeavesHavingRequeuedTheMessage() {
    giveUpOn("e1", DeadLetterStore.Reason.RETRIES_EXHAUSTED, "java.net.ConnectException: refused");

    // Nothing is known up front — not even that there is one. This is the step that had no answer.
    DeadLetter found = deadLetters.list(null, 20).items().getFirst();

    assertEquals("e1", found.eventId());
    assertEquals("SampleEvent", found.type());
    assertEquals(1, found.version());
    assertEquals("ORDER-1", found.subject());
    assertEquals("acme", found.tenantId());
    assertEquals(3, found.attempts());
    assertEquals(DeadLetterStore.Reason.RETRIES_EXHAUSTED, found.reason());
    assertEquals("java.net.ConnectException: refused", found.lastError());
    assertNotNull(found.failedAt(), "when the relay gave up is what makes triage possible");

    // The id from the listing is exactly what replay wants: the two halves fit together.
    assertTrue(store.replay(found.eventId()));
    assertEquals(
        0, jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_dead_letter", Integer.class));
    assertEquals(
        1,
        jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_outbox", Integer.class),
        "the message is back in the outbox for another attempt");
    assertTrue(deadLetters.find("e1").isEmpty(), "and it is no longer among the dead letters");
  }

  @Test
  void pagingRunsNewestFailureFirstAndVisitsEveryRowOnce() {
    giveUpOn("e1", DeadLetterStore.Reason.PERMANENT, "unknown type");
    giveUpOn("e2", DeadLetterStore.Reason.PERMANENT, "unknown type");
    giveUpOn("e3", DeadLetterStore.Reason.RETRIES_EXHAUSTED, "timeout");

    Slice<DeadLetter> first = deadLetters.list(null, 2);
    assertEquals(
        List.of("e3", "e2"),
        first.items().stream().map(DeadLetter::eventId).toList(),
        "the failure an operator wants first is the most recent one");
    assertTrue(first.hasNext());

    Slice<DeadLetter> second = deadLetters.list(first.nextCursor(), 2);
    assertEquals(List.of("e1"), second.items().stream().map(DeadLetter::eventId).toList());
    assertNull(second.nextCursor(), "the last page hands out no cursor");
    assertFalse(second.hasNext());
  }

  @Test
  void anEmptyStoreIsAnEmptyPageNotAnError() {
    Slice<DeadLetter> page = deadLetters.list(null, 20);

    assertTrue(page.items().isEmpty());
    assertNull(page.nextCursor());
  }

  @Test
  void whatTheReaderRefuses() {
    giveUpOn("e1", DeadLetterStore.Reason.PERMANENT, "unknown type");

    assertEquals(Optional.empty(), deadLetters.find("never-existed"));
    assertThrows(IllegalArgumentException.class, () -> deadLetters.list(null, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> deadLetters.list(Cursor.of("not-a-cursor-we-issued"), 10),
        "a cursor is opaque, which means the port must reject one it did not issue");
  }

  private void giveUpOn(String eventId, DeadLetterStore.Reason reason, String lastError) {
    store.store(
        new OutboxMessage(
            eventId,
            "test",
            "SampleEvent",
            1,
            "{}",
            Instant.parse("2026-01-01T00:00:00Z"),
            "ORDER-1",
            "acme",
            "corr",
            null,
            null),
        3,
        reason,
        lastError,
        null,
        null);
  }
}
