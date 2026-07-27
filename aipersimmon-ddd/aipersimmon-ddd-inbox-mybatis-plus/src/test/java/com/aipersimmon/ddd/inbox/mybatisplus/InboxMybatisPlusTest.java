package com.aipersimmon.ddd.inbox.mybatisplus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.inbox.Inbox;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Exercises the MyBatis-Plus inbox against an in-memory H2 database. */
@SpringBootTest(classes = InboxMybatisPlusTest.TestApp.class)
class InboxMybatisPlusTest {

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class TestApp {}

  @Autowired Inbox inbox;
  @Autowired JdbcTemplate jdbc;
  @Autowired InboxMapper inboxMapper;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM aipersimmon_inbox");
  }

  @Test
  void recordsFirstKeyThenDetectsDuplicate() {
    assertFalse(inbox.alreadyProcessed("shop", "k1"), "first delivery should be new");
    assertTrue(
        inbox.alreadyProcessed("shop", "k1"), "redelivery of the same key should be detected");
    assertFalse(inbox.alreadyProcessed("shop", "k2"), "a different key should be new");
  }

  @Test
  void dedupIsScopedPerConsumer() {
    MybatisPlusInbox serviceA = new MybatisPlusInbox(inboxMapper, Clock.systemUTC(), "service-a");
    MybatisPlusInbox serviceB = new MybatisPlusInbox(inboxMapper, Clock.systemUTC(), "service-b");

    assertFalse(serviceA.alreadyProcessed("shop", "evt-1"), "first delivery to service-a is new");
    assertTrue(
        serviceA.alreadyProcessed("shop", "evt-1"), "redelivery to service-a is a duplicate");
    assertFalse(
        serviceB.alreadyProcessed("shop", "evt-1"),
        "the same message id under a different consumer must be handled independently");
  }

  @Test
  void dedupIsScopedPerSource() {
    // A message id is unique only within the source that minted it (CloudEvents: ce_id is
    // scoped by ce_source). Two producers using per-source sequence numbers will both emit
    // "1" — deduplicating on the id alone would drop the second as a phantom duplicate and
    // lose it with no error, no dead letter and no log.
    assertFalse(inbox.alreadyProcessed("billing", "1"), "billing's first message is new");
    assertFalse(
        inbox.alreadyProcessed("shipping", "1"),
        "the same id from a DIFFERENT source is a different message and must still be handled");
    assertTrue(inbox.alreadyProcessed("billing", "1"), "billing's own redelivery is a duplicate");
    assertTrue(inbox.alreadyProcessed("shipping", "1"), "shipping's own redelivery is a duplicate");
  }

  @Test
  void autoConfiguresMybatisPlusInbox() {
    assertInstanceOf(MybatisPlusInbox.class, inbox);
  }

  @Test
  void idBasedAccessDoesNotSilentlyIgnoreTheConsumerScope() {
    // Two consumers recorded the same producer-assigned message id.
    new MybatisPlusInbox(inboxMapper, Clock.systemUTC(), "service-a")
        .alreadyProcessed("shop", "evt-1");
    new MybatisPlusInbox(inboxMapper, Clock.systemUTC(), "service-b")
        .alreadyProcessed("shop", "evt-1");
    assertEquals(2L, inboxMapper.selectCount(null), "each consumer has its own dedup row");

    // The record's identity is the composite (consumer, source, message_key); message_key alone is
    // NOT a unique id. BaseMapper's id-based methods must therefore not be usable to address
    // a row by message_key — otherwise deleteById("evt-1") would wipe BOTH consumers' rows,
    // silently clobbering another service's dedup state.
    assertThrows(
        Exception.class,
        () -> inboxMapper.deleteById("evt-1"),
        "id-based access must be rejected, not silently scoped to message_key alone");
    assertEquals(2L, inboxMapper.selectCount(null), "neither consumer's dedup row was clobbered");
  }
}
