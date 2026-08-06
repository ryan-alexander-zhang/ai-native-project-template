package com.aipersimmon.ddd.outbox.mybatisplus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import com.aipersimmon.ddd.outbox.engine.relay.OutboxRelay;
import com.aipersimmon.ddd.outbox.engine.relay.OutboxRelayScheduler;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Switching the schedule off leaves the relay, and takes the lock off the direct path.
 *
 * <p>This is what a large {@code poll-delay-ms} could never do. {@code @Scheduled(fixedDelay)} runs
 * the task first and waits afterwards, so raising the delay does not prevent a poll at startup; and
 * because {@code @SchedulerLock} skips a method whose lock is held — silently, with no error — a
 * caller driving the relay itself could end up doing nothing at all. Anyone who means "I will drive
 * the relay" needs the schedule gone, not merely slowed.
 *
 * <p>Two audiences: an integration test that asserts on what one poll did, and a deployment that
 * relays from a single dedicated instance while the rest only write.
 */
@SpringBootTest(
    classes = OutboxRelayScheduleDisabledTest.TestApp.class,
    properties = "aipersimmon.ddd.outbox.relay.enabled=false")
class OutboxRelayScheduleDisabledTest {

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

  @Autowired ObjectProvider<OutboxRelayScheduler> scheduler;
  @Autowired OutboxRelay relay;
  @Autowired JdbcTemplate jdbc;

  @Test
  void nothingPollsOnItsOwn() {
    assertEquals(
        Optional.empty(),
        Optional.ofNullable(scheduler.getIfAvailable()),
        "the schedule is gone, so no poll happens behind the caller's back");
  }

  @Test
  void theRelayIsStillThereAndTakesNoLock() {
    assertNotNull(relay, "switching off the schedule must not remove the relay itself");

    relay.relay();

    assertEquals(
        Integer.valueOf(0),
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM shedlock WHERE name = 'aipersimmon-outbox-relay'", Integer.class),
        "a direct call takes no lock, so no held lock can silently skip it");
  }
}
