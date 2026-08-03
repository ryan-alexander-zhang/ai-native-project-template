package com.aipersimmon.ddd.outbox.mybatisplus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.cqrs.page.Slice;
import com.aipersimmon.ddd.outbox.DeadLetter;
import com.aipersimmon.ddd.outbox.DeadLetterStore;
import com.aipersimmon.ddd.outbox.DeadLetters;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The MyBatis-Plus backend's counterpart to the JDBC starter's dead-letter read test: an operator
 * finds what the relay gave up on, pages it newest failure first, and hands the id straight to
 * replay. The two backends must answer identically — a consumer swapping one for the other should
 * not discover that their operations screen now pages the other way round.
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
  void theListingCarriesWhatTriageNeedsAndFeedsReplayDirectly() {
    giveUpOn("e1", DeadLetterStore.Reason.RETRIES_EXHAUSTED, "java.net.ConnectException: refused");

    DeadLetter found = deadLetters.list(null, 20).items().getFirst();

    assertEquals("e1", found.eventId());
    assertEquals("SampleEvent", found.type());
    assertEquals("acme", found.tenantId());
    assertEquals(3, found.attempts());
    assertEquals(DeadLetterStore.Reason.RETRIES_EXHAUSTED, found.reason());
    assertEquals("java.net.ConnectException: refused", found.lastError());

    assertTrue(store.replay(found.eventId()));
    assertTrue(deadLetters.find("e1").isEmpty());
  }

  @Test
  void pagingRunsNewestFailureFirstAndVisitsEveryRowOnce() {
    giveUpOn("e1", DeadLetterStore.Reason.PERMANENT, "unknown type");
    giveUpOn("e2", DeadLetterStore.Reason.PERMANENT, "unknown type");
    giveUpOn("e3", DeadLetterStore.Reason.RETRIES_EXHAUSTED, "timeout");

    Slice<DeadLetter> first = deadLetters.list(null, 2);
    Slice<DeadLetter> second = deadLetters.list(first.nextCursor(), 2);

    assertEquals(List.of("e3", "e2"), first.items().stream().map(DeadLetter::eventId).toList());
    assertEquals(List.of("e1"), second.items().stream().map(DeadLetter::eventId).toList());
    assertNull(second.nextCursor(), "the last page hands out no cursor");
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
