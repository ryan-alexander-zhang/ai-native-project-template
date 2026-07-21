package com.aipersimmon.ddd.saga.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.saga.Deadline;
import com.aipersimmon.ddd.saga.DeadlineHandler;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies the durable JDBC deadline scheduler: a scheduled deadline is a table row that the poll
 * fires once its time passes (deleting it), a not-yet-due or cancelled deadline does not fire,
 * rescheduling replaces the row, and — the point of being durable — a deadline scheduled by one
 * instance is fired by a different instance polling the same table, since the state lives in the
 * database, not in memory.
 */
@SpringBootTest
class JdbcDeadlineSchedulerTest {

  @Autowired JdbcTemplate jdbc;

  private CapturingHandler handler;
  private JdbcDeadlineScheduler scheduler;

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM aipersimmon_deadline");
    handler = new CapturingHandler();
    scheduler = newScheduler();
  }

  @Test
  void firesADueDeadlineAndDeletesItsRow() {
    scheduler.schedule(new Deadline("order-1", "confirm", Instant.now().minusSeconds(1)));
    assertEquals(1, rowCount());

    scheduler.poll();

    assertEquals(1, handler.fired.size());
    assertEquals("order-1", handler.fired.get(0).correlationId());
    assertEquals(0, rowCount(), "a fired deadline's row is deleted");
  }

  @Test
  void doesNotFireADeadlineBeforeItsTime() {
    scheduler.schedule(new Deadline("order-1", "confirm", Instant.now().plusSeconds(3600)));

    scheduler.poll();

    assertTrue(handler.fired.isEmpty());
    assertEquals(1, rowCount(), "a not-yet-due deadline stays pending");
  }

  @Test
  void cancelRemovesAPendingDeadline() {
    scheduler.schedule(new Deadline("order-1", "confirm", Instant.now().minusSeconds(1)));

    scheduler.cancel("order-1", "confirm");
    scheduler.poll();

    assertTrue(handler.fired.isEmpty());
    assertEquals(0, rowCount());
  }

  @Test
  void reschedulingTheSameDeadlineReplacesIt() {
    scheduler.schedule(new Deadline("order-1", "confirm", Instant.now().plusSeconds(3600)));
    scheduler.schedule(new Deadline("order-1", "confirm", Instant.now().minusSeconds(1)));

    assertEquals(1, rowCount(), "still one row for the same key");
    scheduler.poll();
    assertEquals(1, handler.fired.size(), "fires once, at the rescheduled (now-past) time");
  }

  @Test
  void aDeadlineSurvivesToBeFiredByAnotherInstance() {
    // One instance schedules the deadline...
    scheduler.schedule(new Deadline("order-1", "confirm", Instant.now().minusSeconds(1)));

    // ...and a different scheduler instance over the same table fires it, because
    // the pending deadline lives in the database, not in the scheduler's memory.
    CapturingHandler otherHandler = new CapturingHandler();
    JdbcDeadlineScheduler otherInstance =
        new JdbcDeadlineScheduler(jdbc, () -> otherHandler, Clock.systemUTC(), 100);

    otherInstance.poll();

    assertEquals(1, otherHandler.fired.size());
    assertEquals(0, rowCount());
  }

  private JdbcDeadlineScheduler newScheduler() {
    return new JdbcDeadlineScheduler(jdbc, () -> handler, Clock.systemUTC(), 100);
  }

  private int rowCount() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_deadline", Integer.class);
  }

  static final class CapturingHandler implements DeadlineHandler {
    final List<Deadline> fired = new ArrayList<>();

    @Override
    public void onDeadline(Deadline deadline) {
      fired.add(deadline);
    }
  }

  @Configuration
  @EnableAutoConfiguration
  static class TestApp {}
}
