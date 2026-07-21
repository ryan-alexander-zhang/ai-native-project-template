package com.aipersimmon.ddd.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.core.error.ErrorCode;
import org.junit.jupiter.api.Test;

class ConcurrencyConflictExceptionTest {

  private enum SampleCode implements ErrorCode {
    CONFLICT;

    @Override
    public String code() {
      return "sample.conflict";
    }
  }

  @Test
  void isAnApplicationException() {
    assertTrue(
        ApplicationException.class.isAssignableFrom(ConcurrencyConflictException.class),
        "so callers catching ApplicationException also catch conflicts");
  }

  @Test
  void messageOnly() {
    ConcurrencyConflictException ex = new ConcurrencyConflictException("stale");

    assertEquals("stale", ex.getMessage());
    assertNull(ex.getCause());
    assertTrue(ex.errorCode().isEmpty());
  }

  @Test
  void messageAndCause() {
    Throwable cause = new IllegalStateException("root");
    ConcurrencyConflictException ex = new ConcurrencyConflictException("stale", cause);

    assertEquals("stale", ex.getMessage());
    assertSame(cause, ex.getCause());
    assertTrue(ex.errorCode().isEmpty());
  }

  @Test
  void codeMessageAndCause() {
    Throwable cause = new IllegalStateException("root");
    ConcurrencyConflictException ex =
        new ConcurrencyConflictException(SampleCode.CONFLICT, "stale", cause);

    assertEquals("stale", ex.getMessage());
    assertSame(cause, ex.getCause());
    assertSame(SampleCode.CONFLICT, ex.errorCode().orElseThrow());
  }
}
