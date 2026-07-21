package com.aipersimmon.ddd.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.core.error.ErrorCode;
import org.junit.jupiter.api.Test;

class EntityNotFoundExceptionTest {

  private enum SampleCode implements ErrorCode {
    NOT_FOUND;

    @Override
    public String code() {
      return "sample.not-found";
    }
  }

  @Test
  void isAnApplicationException() {
    assertTrue(ApplicationException.class.isAssignableFrom(EntityNotFoundException.class));
  }

  @Test
  void messageOnly() {
    EntityNotFoundException ex = new EntityNotFoundException("no such order");

    assertEquals("no such order", ex.getMessage());
    assertTrue(ex.errorCode().isEmpty());
  }

  @Test
  void codeAndMessage() {
    EntityNotFoundException ex = new EntityNotFoundException(SampleCode.NOT_FOUND, "no such order");

    assertEquals("no such order", ex.getMessage());
    assertSame(SampleCode.NOT_FOUND, ex.errorCode().orElseThrow());
  }
}
