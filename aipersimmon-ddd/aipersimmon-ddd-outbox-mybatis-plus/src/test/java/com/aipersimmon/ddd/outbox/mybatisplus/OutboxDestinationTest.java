package com.aipersimmon.ddd.outbox.mybatisplus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.outbox.DeadLetterStore;
import com.aipersimmon.ddd.outbox.EventDestinations;
import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.tenancy.Tenants;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The destination column on the MyBatis-Plus store. The relay's behaviour around it is the engine's
 * and is tested once, on the JDBC store; what is a second implementation here is moving the column
 * into and out of the two tables — including across a dead-letter replay, which copies a row back
 * into the outbox and would otherwise resurrect an externalized event as in-process.
 */
@SpringBootTest(
    classes = OutboxDestinationTest.TestApp.class,
    properties = "aipersimmon.ddd.outbox.relay.enabled=false")
class OutboxDestinationTest {

  private static final String TOPIC = "ordering.events";

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class TestApp {

    @Bean
    EventDestinations eventDestinations() {
      return (type, version) ->
          Externalised.TYPE.equals(type) ? Optional.of(TOPIC) : Optional.empty();
    }

    /** Present only so dispatcher selection has something to pick. */
    @Bean
    OutboxDispatcher noOpDispatcher() {
      return message -> {};
    }
  }

  @EventType(name = Externalised.TYPE, version = 1)
  record Externalised(String id) implements IntegrationEvent {
    static final String TYPE = "com.example.Externalised";

    @Override
    public String subject() {
      return id;
    }
  }

  @EventType(name = "com.example.StaysLocal", version = 1)
  record StaysLocal(String id) implements IntegrationEvent {
    @Override
    public String subject() {
      return id;
    }
  }

  @Autowired IntegrationEvents events;
  @Autowired DeadLetterStore deadLetterStore;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM aipersimmon_outbox");
    jdbc.update("DELETE FROM aipersimmon_dead_letter");
  }

  private void publish(IntegrationEvent event) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> events.publish(event, CommandContext.root(Tenants.ROOT.value(), "msg-1")));
  }

  private String destinationOf(String table, String eventId) {
    return jdbc.queryForObject(
        "SELECT destination FROM " + table + " WHERE event_id = ?", String.class, eventId);
  }

  private String onlyEventId(String table) {
    return jdbc.queryForObject("SELECT event_id FROM " + table, String.class);
  }

  @Test
  void theWriterRecordsWhereTheEventIsGoing() {
    publish(new Externalised("o-1"));

    assertEquals(
        TOPIC,
        destinationOf("aipersimmon_outbox", onlyEventId("aipersimmon_outbox")),
        "the destination resolved at publish time must be on the row, not re-derived later");
  }

  @Test
  void anEventThatIsNotExternalizedRecordsNoDestination() {
    publish(new StaysLocal("o-2"));

    assertNull(
        destinationOf("aipersimmon_outbox", onlyEventId("aipersimmon_outbox")),
        "no destination means in-process, which is the default reach");
  }

  @Test
  void aDeadLetteredRowKeepsItsDestinationAndGetsItBackOnReplay() {
    publish(new Externalised("o-3"));
    String eventId = onlyEventId("aipersimmon_outbox");
    deadLetterStore.store(
        message(eventId), 3, DeadLetterStore.Reason.RETRIES_EXHAUSTED, "simulated");

    assertEquals(
        TOPIC,
        destinationOf("aipersimmon_dead_letter", eventId),
        "the dead letter carries the destination, or a replay could not know where it was going");

    assertTrue(deadLetterStore.replay(eventId), "the row is requeued");

    assertEquals(
        TOPIC,
        destinationOf("aipersimmon_outbox", eventId),
        "a replayed row must come back destined for the broker: resurrecting it as in-process "
            + "would be the same silent loss through a second door");
  }

  /** Reads the row back the way the relay would hand it to the dead-letter store. */
  private com.aipersimmon.ddd.outbox.OutboxMessage message(String eventId) {
    return jdbc.queryForObject(
        "SELECT event_id, source, type, version, payload, occurred_at, subject, tenant_id, "
            + "correlation_id, causation_id, destination FROM aipersimmon_outbox WHERE event_id = ?",
        (rs, n) ->
            new com.aipersimmon.ddd.outbox.OutboxMessage(
                rs.getString("event_id"),
                rs.getString("source"),
                rs.getString("type"),
                rs.getInt("version"),
                rs.getString("payload"),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("subject"),
                rs.getString("tenant_id"),
                rs.getString("correlation_id"),
                rs.getString("causation_id"),
                rs.getString("destination")),
        eventId);
  }
}
