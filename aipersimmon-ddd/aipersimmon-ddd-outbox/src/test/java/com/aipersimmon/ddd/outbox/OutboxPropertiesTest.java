package com.aipersimmon.ddd.outbox;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The bound outbox config validates at startup ({@link OutboxProperties#afterPropertiesSet()}), so
 * values that would silently misbehave fail loud instead of surfacing later as no-progress polls,
 * premature dead-lettering, an inverted backoff schedule, or deletion of still-live rows. The
 * shipped defaults are valid.
 */
class OutboxPropertiesTest {

  @Test
  void shippedDefaultsAreValid() {
    assertDoesNotThrow(() -> new OutboxProperties().afterPropertiesSet());
  }

  @Test
  void rejectsNonPositiveBatchSize() {
    OutboxProperties props = new OutboxProperties();
    props.setBatchSize(0);
    IllegalStateException e = assertThrows(IllegalStateException.class, props::afterPropertiesSet);
    assertTrue(e.getMessage().contains("batch-size"));
  }

  @Test
  void rejectsNonPositiveMaxAttempts() {
    OutboxProperties props = new OutboxProperties();
    props.setMaxAttempts(0);
    IllegalStateException e = assertThrows(IllegalStateException.class, props::afterPropertiesSet);
    assertTrue(e.getMessage().contains("max-attempts"));
  }

  @Test
  void rejectsNegativeBaseBackoff() {
    OutboxProperties props = new OutboxProperties();
    props.getRetry().setBaseBackoffMs(-1);
    assertThrows(IllegalStateException.class, props::afterPropertiesSet);
  }

  @Test
  void rejectsMaxBackoffBelowBaseBackoff() {
    OutboxProperties props = new OutboxProperties();
    props.getRetry().setBaseBackoffMs(5000);
    props.getRetry().setMaxBackoffMs(1000);
    IllegalStateException e = assertThrows(IllegalStateException.class, props::afterPropertiesSet);
    assertTrue(e.getMessage().contains("max-backoff-ms"));
  }

  @Test
  void rejectsNegativeRetention() {
    OutboxProperties props = new OutboxProperties();
    props.getCleanup().setRetentionSeconds(-1);
    IllegalStateException e = assertThrows(IllegalStateException.class, props::afterPropertiesSet);
    assertTrue(e.getMessage().contains("retention-seconds"));
  }
}
