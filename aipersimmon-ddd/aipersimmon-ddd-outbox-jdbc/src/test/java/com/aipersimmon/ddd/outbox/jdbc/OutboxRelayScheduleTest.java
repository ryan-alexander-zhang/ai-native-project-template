package com.aipersimmon.ddd.outbox.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import com.aipersimmon.ddd.outbox.engine.relay.OutboxRelay;
import com.aipersimmon.ddd.outbox.engine.relay.OutboxRelayScheduler;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The schedule exists by default, so a deployment that changes nothing still drains its outbox —
 * and it takes no lock, so delivery does not sit behind a single holder that a killed instance
 * would leave held. Several instances polling at once is safe because the rows themselves are
 * claimed; that claim is covered by {@link OutboxRelayClaimTest}.
 *
 * <p>The trigger stays a separate bean from {@link OutboxRelay} so a dedicated relay instance, or a
 * test, can switch the schedule off and drive delivery itself — see {@link
 * OutboxRelayScheduleDisabledTest}.
 */
@SpringBootTest(classes = OutboxRelayScheduleTest.TestApp.class)
class OutboxRelayScheduleTest {

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class TestApp {
    @Bean
    OutboxDispatcher capturingDispatcher() {
      return new CapturingDispatcher();
    }
  }

  static final class CapturingDispatcher implements OutboxDispatcher {
    final List<OutboxMessage> messages = new CopyOnWriteArrayList<>();

    @Override
    public void dispatch(OutboxMessage message) {
      messages.add(message);
    }
  }

  @Autowired OutboxRelayScheduler scheduler;
  @Autowired CapturingDispatcher dispatcher;
  @Autowired JdbcTemplate jdbc;

  @Test
  void theScheduledPollDeliversWithoutTakingAnyLock() {
    jdbc.update(
        "INSERT INTO aipersimmon_outbox (event_id, source, type, version, payload, occurred_at, "
            + "subject, correlation_id, sent, attempts, created_at) "
            + "VALUES ('scheduled-1', 'test', 'SampleEvent', 1, '{}', ?, 'agg-1', 'corr', ?, 0, ?)",
        Timestamp.from(Instant.now()),
        false,
        Timestamp.from(Instant.now()));

    scheduler.poll();

    assertTrue(
        dispatcher.messages.stream().anyMatch(m -> "scheduled-1".equals(m.eventId())),
        "the shipped schedule must drain the outbox with no configuration at all");
    assertEquals(
        Integer.valueOf(0),
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM shedlock WHERE name LIKE '%outbox-relay%'", Integer.class),
        "the relay must not take a schedule-wide lock: a killed instance cannot release one, and "
            + "every other instance would then skip its poll until that lock expired");
  }
}
