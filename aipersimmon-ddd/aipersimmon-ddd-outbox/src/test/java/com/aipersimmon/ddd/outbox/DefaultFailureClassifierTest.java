package com.aipersimmon.ddd.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aipersimmon.ddd.integration.MalformedIntegrationEventException;
import com.aipersimmon.ddd.integration.UnknownIntegrationEventException;
import com.aipersimmon.ddd.outbox.FailureClassifier.Failure;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Test;

/**
 * The provably-hopeless causes are permanent (so the relay dead-letters them without burning
 * retries); everything else is transient. Cause-chain wrapping does not hide a permanent cause.
 * This is the same permanent set the Kafka consumer treats as not-retryable, so the two transports
 * agree.
 */
class DefaultFailureClassifierTest {

  private final FailureClassifier classifier = new DefaultFailureClassifier();

  @Test
  void unknownEventTypeIsPermanent() {
    assertEquals(
        Failure.PERMANENT,
        classifier.classify(new UnknownIntegrationEventException("com.example.Unknown", 1)));
  }

  @Test
  void malformedEventIsPermanent() {
    assertEquals(
        Failure.PERMANENT,
        classifier.classify(new MalformedIntegrationEventException("missing ce_id")));
  }

  @Test
  void jsonProcessingFailureIsPermanentEvenWhenWrapped() {
    Throwable wrapped = new IllegalStateException("reconstruct failed", new StubJsonException());
    assertEquals(Failure.PERMANENT, classifier.classify(wrapped));
  }

  @Test
  void anythingElseIsTransient() {
    assertEquals(Failure.TRANSIENT, classifier.classify(new IllegalStateException("broker down")));
    assertEquals(Failure.TRANSIENT, classifier.classify(new RuntimeException()));
  }

  @Test
  void aSelfReferentialCauseDoesNotLoop() {
    assertEquals(Failure.TRANSIENT, classifier.classify(new SelfCausing()));
  }

  /** A concrete JsonProcessingException for the wrapped-cause case. */
  static final class StubJsonException extends JsonProcessingException {
    StubJsonException() {
      super("malformed");
    }
  }

  /** Its own cause — exercises the loop guard (the JDK forbids initCause(this)). */
  static final class SelfCausing extends RuntimeException {
    @Override
    public synchronized Throwable getCause() {
      return this;
    }
  }
}
