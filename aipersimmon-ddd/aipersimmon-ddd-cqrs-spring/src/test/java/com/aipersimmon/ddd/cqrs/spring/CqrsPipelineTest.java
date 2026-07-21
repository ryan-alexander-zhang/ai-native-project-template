package com.aipersimmon.ddd.cqrs.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.core.event.DomainEvent;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.aipersimmon.ddd.cqrs.Query;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.aipersimmon.ddd.cqrs.QueryHandler;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Drives the full command pipeline end to end: the bus routes a command to its handler through the
 * logging → validation → transaction chain; the handler drains its aggregate's domain events where
 * it saves it, inside the transaction opened by the transaction interceptor. Asserts the happy
 * path, rollback on handler failure (no row, no event delivered), validation rejecting a command
 * before any transaction, and the query bus.
 */
@SpringBootTest
class CqrsPipelineTest {

  @Autowired CommandBus commandBus;
  @Autowired QueryBus queryBus;
  @Autowired JdbcTemplate jdbc;
  @Autowired CapturingDomainEvents publishedEvents;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM thing");
    publishedEvents.events.clear();
  }

  @Test
  void createsRowAndDrainsEventInOneTransaction() {
    String id = commandBus.send(new PlaceThing("widget"));

    assertEquals("thing-widget", id);
    assertEquals(1, count());
    assertEquals(1, publishedEvents.events.size());
    assertTrue(publishedEvents.events.get(0) instanceof ThingCreated);
  }

  @Test
  void handlerFailureRollsBackTheRowAndDrainsNothing() {
    assertThrows(RuntimeException.class, () -> commandBus.send(new PlaceThing("boom")));

    assertEquals(0, count());
    assertTrue(publishedEvents.events.isEmpty());
  }

  @Test
  void validationRejectsBlankCommandBeforeAnyTransaction() {
    assertThrows(ConstraintViolationException.class, () -> commandBus.send(new PlaceThing("  ")));

    assertEquals(0, count());
    assertTrue(publishedEvents.events.isEmpty());
  }

  @Test
  void queryBusAnswersFromTheReadSide() {
    commandBus.send(new PlaceThing("a"));
    commandBus.send(new PlaceThing("b"));

    assertEquals(2, queryBus.ask(new CountThings()));
  }

  private int count() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM thing", Integer.class);
  }

  // --- test fixtures -----------------------------------------------------

  record PlaceThing(@NotBlank String name) implements Command<String> {}

  record CountThings() implements Query<Integer> {}

  record ThingCreated(String id) implements DomainEvent {}

  static final class Thing extends AbstractAggregateRoot<String> {
    private final String id;

    Thing(String id) {
      this.id = id;
      registerEvent(new ThingCreated(id));
    }

    @Override
    public String id() {
      return id;
    }
  }

  @Configuration
  @EnableAutoConfiguration
  static class TestApp {

    @Bean
    CapturingDomainEvents capturingDomainEvents() {
      return new CapturingDomainEvents();
    }

    @Bean
    PlaceThingHandler placeThingHandler(JdbcTemplate jdbc, DomainEvents domainEvents) {
      return new PlaceThingHandler(jdbc, domainEvents);
    }

    @Bean
    CountThingsHandler countThingsHandler(JdbcTemplate jdbc) {
      return new CountThingsHandler(jdbc);
    }
  }

  // Concrete handler classes: their command/query type is retained in the class
  // signature, so the bus can index them by it (a lambda would erase it).
  static final class PlaceThingHandler implements CommandHandler<PlaceThing, String> {
    private final JdbcTemplate jdbc;
    private final DomainEvents domainEvents;

    PlaceThingHandler(JdbcTemplate jdbc, DomainEvents domainEvents) {
      this.jdbc = jdbc;
      this.domainEvents = domainEvents;
    }

    @Override
    public String handle(PlaceThing command, CommandContext context) {
      String id = "thing-" + command.name();
      jdbc.update("INSERT INTO thing(id) VALUES (?)", id);
      domainEvents.publishAndClear(new Thing(id));
      if ("boom".equals(command.name())) {
        throw new IllegalStateException("boom");
      }
      return id;
    }
  }

  static final class CountThingsHandler implements QueryHandler<CountThings, Integer> {
    private final JdbcTemplate jdbc;

    CountThingsHandler(JdbcTemplate jdbc) {
      this.jdbc = jdbc;
    }

    @Override
    public Integer handle(CountThings query) {
      return jdbc.queryForObject("SELECT COUNT(*) FROM thing", Integer.class);
    }
  }

  /**
   * A transaction-aware publisher: it records an event only after the surrounding transaction
   * commits, so an event drained during a command that later rolls back is never delivered
   * (matching how an outbox or an {@code @TransactionalEventListener} behaves). Outside a
   * transaction it records immediately.
   */
  static final class CapturingDomainEvents implements DomainEvents {
    final List<DomainEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void publish(DomainEvent event) {
      if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
              @Override
              public void afterCommit() {
                events.add(event);
              }
            });
      } else {
        events.add(event);
      }
    }
  }
}
