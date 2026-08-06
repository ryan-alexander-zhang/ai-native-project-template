package com.aipersimmon.ddd.outbox.mybatisplus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.outbox.DeadLetterStore;
import com.aipersimmon.ddd.outbox.EventDestinations;
import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import com.aipersimmon.ddd.outbox.engine.relay.OutboxRelay;
import com.aipersimmon.ddd.tenancy.Tenants;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * Where an event goes is decided when it is written and stored on the row, not re-decided from the
 * annotations of whatever code is deployed when the relay reaches it.
 *
 * <p>The dispatcher here is deliberately one that admits it cannot reach an external target, which
 * is the situation that used to lose events silently: a row destined for a broker fell through to
 * in-process delivery and was marked sent — no exception, no dead letter, no consumer lag. These
 * pin that it now fails, retries, and ends up visible.
 */
@SpringBootTest(
    classes = OutboxDestinationTest.TestApp.class,
    properties = {
      "aipersimmon.ddd.outbox.relay.enabled=false",
      "aipersimmon.ddd.outbox.max-attempts=2",
      "aipersimmon.ddd.outbox.retry.base-backoff-ms=0",
      "aipersimmon.ddd.outbox.retry.max-backoff-ms=0"
    })
class OutboxDestinationTest {

  private static final String TOPIC = "ordering.events";

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class TestApp {

    /** Stands in for a transport starter's routing table: one event is externalized, one is not. */
    @Bean
    EventDestinations eventDestinations() {
      return (type, version) ->
          Externalised.TYPE.equals(type) ? Optional.of(TOPIC) : Optional.empty();
    }

    @Bean
    LocalOnlyDispatcher localOnlyDispatcher() {
      return new LocalOnlyDispatcher();
    }
  }

  /** An in-process dispatcher: it delivers, but it cannot reach anything outside the JVM. */
  static class LocalOnlyDispatcher implements OutboxDispatcher {
    final List<String> dispatched = new CopyOnWriteArrayList<>();

    @Override
    public void dispatch(OutboxMessage message) {
      dispatched.add(message.eventId());
    }

    @Override
    public boolean reachesExternalTargets() {
      return false;
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
  @Autowired OutboxRelay relay;
  @Autowired DeadLetterStore deadLetterStore;
  @Autowired LocalOnlyDispatcher dispatcher;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM aipersimmon_outbox");
    jdbc.update("DELETE FROM aipersimmon_dead_letter");
    dispatcher.dispatched.clear();
  }

  /**
   * The writer refuses to write outside a transaction, so publish the way a command handler does.
   */
  private void publish(IntegrationEvent event) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> events.publish(event, CommandContext.root(Tenants.ROOT, "msg-1")));
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
  void aRowDestinedForABrokerIsNeverDeliveredInProcessAndMarkedSent() {
    publish(new Externalised("o-3"));
    String eventId = onlyEventId("aipersimmon_outbox");

    relay.relay();

    assertEquals(
        List.of(),
        dispatcher.dispatched,
        "a dispatcher that cannot reach the destination must not be handed the row at all");
    assertEquals(
        Boolean.FALSE,
        jdbc.queryForObject(
            "SELECT sent FROM aipersimmon_outbox WHERE event_id = ?", Boolean.class, eventId),
        "and the row must not be archived as sent — that is the silent loss being prevented");
    assertEquals(
        Integer.valueOf(1),
        jdbc.queryForObject(
            "SELECT attempts FROM aipersimmon_outbox WHERE event_id = ?", Integer.class, eventId),
        "it counts as a failed attempt");
    assertNotNull(
        jdbc.queryForObject(
            "SELECT next_attempt_at FROM aipersimmon_outbox WHERE event_id = ?",
            Timestamp.class,
            eventId),
        "and is retried rather than given up on at once: a missing transport is often a rolling "
            + "deploy, not a verdict");
  }

  @Test
  void anUndeliverableDestinationEndsAsAVisibleDeadLetter() {
    publish(new Externalised("o-4"));

    relay.relay(); // attempt 1
    relay.relay(); // attempt 2 reaches max-attempts

    assertEquals(
        0,
        (int) jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_outbox", Integer.class),
        "the row leaves the outbox");
    assertEquals(
        1,
        (int) jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_dead_letter", Integer.class),
        "and is preserved where an operator can see it, instead of vanishing as sent");
    assertTrue(
        jdbc.queryForObject("SELECT last_error FROM aipersimmon_dead_letter", String.class)
            .contains(TOPIC),
        "the recorded error names the destination that could not be reached");
  }

  @Test
  void aDeadLetterRemembersItsDestinationAcrossAReplay() {
    publish(new Externalised("o-5"));
    String eventId = onlyEventId("aipersimmon_outbox");
    relay.relay();
    relay.relay();

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
}
