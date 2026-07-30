package com.aipersimmon.ddd.outbox.mybatisplus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.engine.store.OutboxLease;
import com.aipersimmon.ddd.outbox.engine.store.OutboxStore;
import com.aipersimmon.ddd.outbox.engine.store.PendingMessage;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The claim contract on the MyBatis-Plus store. The relay that runs on it is the engine's and is
 * tested once, on the JDBC store; what is genuinely a second implementation is this store's claim —
 * hand-written candidate SQL plus an update wrapper — so these are the cases that keep the two
 * spellings of it equivalent.
 */
@SpringBootTest(
    classes = OutboxRelayClaimTest.TestApp.class,
    properties = "aipersimmon.ddd.outbox.relay.enabled=false")
class OutboxRelayClaimTest {

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class TestApp {

    /** Present only so dispatcher selection has something to pick. */
    @Bean
    OutboxDispatcher noOpDispatcher() {
      return message -> {};
    }
  }

  @Autowired OutboxStore store;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM aipersimmon_outbox");
  }

  private void insert(String eventId, String subject, long createdOffsetSeconds) {
    jdbc.update(
        "INSERT INTO aipersimmon_outbox (event_id, source, type, version, payload, occurred_at, "
            + "subject, correlation_id, causation_id, sent, attempts, created_at) "
            + "VALUES (?, 'test', 'SampleEvent', 1, '{}', ?, ?, 'corr', NULL, ?, 0, ?)",
        eventId,
        Timestamp.from(Instant.now()),
        subject,
        false,
        Timestamp.from(Instant.now().plusSeconds(createdOffsetSeconds)));
  }

  private static OutboxLease lease(String owner, Instant until) {
    return new OutboxLease(owner, owner + "-token", until);
  }

  private static List<String> ids(List<PendingMessage> claimed) {
    return claimed.stream().map(p -> p.message().eventId()).toList();
  }

  private String leaseTokenOf(String eventId) {
    return jdbc.queryForObject(
        "SELECT lease_token FROM aipersimmon_outbox WHERE event_id = ?", String.class, eventId);
  }

  @Test
  void twoInstancesClaimingAtTheSameInstantTakeDisjointRows() {
    insert("a1", null, 0);
    insert("b1", null, 1);
    Instant now = Instant.now();

    List<PendingMessage> first = store.claimDue(now, 10, 1, lease("node-A", now.plusSeconds(60)));
    List<PendingMessage> second = store.claimDue(now, 10, 10, lease("node-B", now.plusSeconds(60)));

    assertEquals(List.of("a1"), ids(first), "the first claim takes the oldest row");
    assertEquals(
        List.of("b1"),
        ids(second),
        "a concurrent claim must never be handed a row the first one already holds");
  }

  @Test
  void aRowHeldByAKilledInstanceIsClaimableOnceItsLeaseExpires() {
    insert("e1", "agg-1", 0);
    Instant now = Instant.now();
    store.claimDue(now, 10, 10, lease("node-A", now.minusSeconds(1)));

    assertEquals(
        List.of("e1"),
        ids(store.claimDue(now, 10, 10, lease("node-B", now.plusSeconds(60)))),
        "an expired lease is the whole recovery mechanism: nothing has to notice the death");
    assertEquals("node-B-token", leaseTokenOf("e1"), "and the new holder owns it outright");
  }

  @Test
  void onlyTheHeadOfAnAggregateIsClaimableUntilItLeavesTheLiveQueue() {
    insert("e1", "agg-1", 0);
    insert("e2", "agg-1", 10);
    Instant now = Instant.now();

    assertEquals(
        List.of("e1"),
        ids(store.claimDue(now, 10, 10, lease("node-A", now.plusSeconds(60)))),
        "admitting only the head is what keeps an aggregate ordered without coordinating pollers");
    assertNull(leaseTokenOf("e2"), "a blocked row is not leased either — it was never a candidate");

    store.markSent(List.of("e1"), now);

    assertEquals(
        List.of("e2"),
        ids(store.claimDue(now, 10, 10, lease("node-B", now.plusSeconds(60)))),
        "once the head is sent its successor is the head, and any instance may take it");
  }

  @Test
  void everyRelayWriteEndsTheClaimSoNoRowWaitsOutALeaseItNoLongerNeeds() {
    insert("sent-1", null, 0);
    insert("retried-1", null, 1);
    insert("backed-off-1", null, 2);
    insert("released-1", null, 3);
    Instant now = Instant.now();
    store.claimDue(now, 10, 10, lease("node-A", now.plusSeconds(600)));

    store.markSent(List.of("sent-1"), now);
    store.scheduleRetry("retried-1", now.plusSeconds(5));
    store.backOffWithoutAttempt("backed-off-1", now.plusSeconds(5));
    store.release(List.of("released-1"));

    assertNull(leaseTokenOf("sent-1"), "a delivered row holds nothing");
    assertNull(leaseTokenOf("retried-1"), "nor does one that is backing off");
    assertNull(leaseTokenOf("backed-off-1"), "nor one backed off without an attempt");
    assertNull(leaseTokenOf("released-1"), "nor one handed back");
    assertEquals(
        Integer.valueOf(1),
        jdbc.queryForObject(
            "SELECT attempts FROM aipersimmon_outbox WHERE event_id = ?",
            Integer.class,
            "retried-1"),
        "clearing the lease must not disturb the attempt count a retry just bumped");
    assertEquals(
        Integer.valueOf(0),
        jdbc.queryForObject(
            "SELECT attempts FROM aipersimmon_outbox WHERE event_id = ?",
            Integer.class,
            "backed-off-1"),
        "and a backoff without an attempt still counts none");
    assertEquals(
        List.of("released-1"),
        ids(store.claimDue(now, 10, 10, lease("node-B", now.plusSeconds(60)))),
        "a released row is claimable at once, while the backing-off ones wait for their next attempt");
  }
}
