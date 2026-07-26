package com.aipersimmon.ddd.outbox.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxMessage;
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
 * The schedule exists by default and its poll is lock-guarded, so a deployment that changes nothing
 * still drains its outbox and several instances do not poll the same rows at once.
 *
 * <p>The lock lives on the scheduled poll rather than on {@link OutboxRelay#relay()} deliberately:
 * it guards the <em>schedule</em>. A caller invoking the relay directly is one deliberate act and
 * needs no lock — and must not be silently skipped by one, which is what used to happen when the
 * startup poll held it. {@link OutboxRelayScheduleDisabledTest} covers that side.
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

  /** Present only so dispatcher selection has something to pick. */
  static final class CapturingDispatcher implements OutboxDispatcher {
    final List<OutboxMessage> messages = new CopyOnWriteArrayList<>();

    @Override
    public void dispatch(OutboxMessage message) {
      messages.add(message);
    }
  }

  @Autowired OutboxRelayScheduler scheduler;
  @Autowired JdbcTemplate jdbc;

  @Test
  void theScheduledPollIsGuardedByShedLock() {
    scheduler.poll();

    assertEquals(
        Integer.valueOf(1),
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM shedlock WHERE name = 'aipersimmon-outbox-relay'", Integer.class),
        "the scheduled poll must take a ShedLock lock so only one instance polls at a time");
  }
}
