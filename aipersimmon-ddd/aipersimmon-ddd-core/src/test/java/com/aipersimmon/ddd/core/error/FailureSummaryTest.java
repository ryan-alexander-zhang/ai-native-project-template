package com.aipersimmon.ddd.core.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class FailureSummaryTest {

  @Test
  void oneThrowableIsItsClassAndMessage() {
    assertEquals(
        "java.lang.IllegalStateException: nope",
        FailureSummary.of(new IllegalStateException("nope")));
  }

  /**
   * The reason this class exists: the outermost frame of the commonest publish failure carries no
   * information, and everything an operator needs is underneath it.
   */
  @Test
  void thecausesAreWhatCarriesTheInformation() {
    Throwable wrapped =
        new IllegalStateException(
            "Send failed",
            new IOException(
                "Topic orders.events not present in metadata after 5000 ms",
                new IllegalArgumentException("This server does not host this topic-partition")));

    String summary = FailureSummary.of(wrapped);

    assertEquals(
        "java.lang.IllegalStateException: Send failed"
            + " <- java.io.IOException: Topic orders.events not present in metadata after 5000 ms"
            + " <- java.lang.IllegalArgumentException: This server does not host this"
            + " topic-partition",
        summary);
  }

  @Test
  void anullMessageIsRecordedRatherThanHidden() {
    assertEquals(
        "java.lang.IllegalStateException: null", FailureSummary.of(new IllegalStateException()));
  }

  /** A summariser that can throw on a failure path is worse than a useless string. */
  @Test
  void nullIsNotAnError() {
    assertEquals("null", FailureSummary.of(null));
  }

  /** A self-referential cause is a thing badly-written exceptions do; it must not loop. */
  @Test
  void aselfReferentialCauseStops() {
    Throwable self =
        new IllegalStateException("loop") {
          @Override
          public synchronized Throwable getCause() {
            return this;
          }
        };

    String summary = FailureSummary.of(self);

    assertTrue(summary.endsWith(": loop"), summary);
    assertEquals(1, occurrences(summary, "loop"));
  }

  /** Depth is bounded, so a deeply nested chain cannot make the column unbounded either. */
  @Test
  void thewalkIsBoundedByDepth() {
    Throwable deep = new IllegalStateException("cause-0");
    for (int i = 1; i <= FailureSummary.MAX_DEPTH + 5; i++) {
      deep = new IllegalStateException("cause-" + i, deep);
    }

    String summary = FailureSummary.of(deep);

    assertEquals(FailureSummary.MAX_DEPTH - 1, occurrences(summary, " <- "));
  }

  @Test
  void thelengthIsBounded() {
    Throwable long1 = new IllegalStateException("x".repeat(FailureSummary.MAX_LENGTH * 2));

    assertEquals(FailureSummary.MAX_LENGTH, FailureSummary.of(long1).length());
  }

  private static int occurrences(String text, String needle) {
    int count = 0;
    for (int from = text.indexOf(needle); from >= 0; from = text.indexOf(needle, from + 1)) {
      count++;
    }
    return count;
  }
}
