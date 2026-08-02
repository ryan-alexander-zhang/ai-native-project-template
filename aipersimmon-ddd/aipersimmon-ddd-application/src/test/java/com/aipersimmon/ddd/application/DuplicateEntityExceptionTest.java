package com.aipersimmon.ddd.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.core.error.ErrorCode;
import org.junit.jupiter.api.Test;

class DuplicateEntityExceptionTest {

  private enum SampleCode implements ErrorCode {
    DUPLICATE;

    @Override
    public String code() {
      return "sample.duplicate";
    }
  }

  @Test
  void isAnApplicationExceptionButNotARetryableConflict() {
    assertTrue(ApplicationException.class.isAssignableFrom(DuplicateEntityException.class));
    // The type distinction is the contract: a retry policy that catches the transient
    // optimistic-lock conflict must never also catch a stable duplicate create.
    assertFalse(
        ConcurrencyConflictException.class.isAssignableFrom(DuplicateEntityException.class));
  }

  @Test
  void messageOnly() {
    DuplicateEntityException ex = new DuplicateEntityException("order 42 already exists");

    assertEquals("order 42 already exists", ex.getMessage());
    assertTrue(ex.errorCode().isEmpty());
  }

  @Test
  void messageAndCause() {
    RuntimeException cause = new RuntimeException("duplicate key");
    DuplicateEntityException ex = new DuplicateEntityException("order 42 already exists", cause);

    assertEquals("order 42 already exists", ex.getMessage());
    assertSame(cause, ex.getCause());
    assertTrue(ex.errorCode().isEmpty());
  }

  @Test
  void codeMessageAndCause() {
    RuntimeException cause = new RuntimeException("duplicate key");
    DuplicateEntityException ex =
        new DuplicateEntityException(SampleCode.DUPLICATE, "order 42 already exists", cause);

    assertEquals("order 42 already exists", ex.getMessage());
    assertSame(cause, ex.getCause());
    assertSame(SampleCode.DUPLICATE, ex.errorCode().orElseThrow());
  }
}
