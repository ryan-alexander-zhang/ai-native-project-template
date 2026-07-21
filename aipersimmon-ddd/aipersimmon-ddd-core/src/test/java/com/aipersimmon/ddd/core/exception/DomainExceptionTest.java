package com.aipersimmon.ddd.core.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.core.error.ErrorCode;
import org.junit.jupiter.api.Test;

class DomainExceptionTest {

  private enum SampleCode implements ErrorCode {
    BOOM;

    @Override
    public String code() {
      return "sample.boom";
    }
  }

  @Test
  void messageOnly_hasNoCauseAndNoCode() {
    DomainException ex = new DomainException("boom");

    assertEquals("boom", ex.getMessage());
    assertNull(ex.getCause());
    assertTrue(ex.errorCode().isEmpty());
  }

  @Test
  void messageAndCause_keepsCauseAndHasNoCode() {
    Throwable cause = new IllegalStateException("root");
    DomainException ex = new DomainException("boom", cause);

    assertEquals("boom", ex.getMessage());
    assertSame(cause, ex.getCause());
    assertTrue(ex.errorCode().isEmpty());
  }

  @Test
  void codeAndMessage_carriesCodeAndHasNoCause() {
    DomainException ex = new DomainException(SampleCode.BOOM, "boom");

    assertEquals("boom", ex.getMessage());
    assertNull(ex.getCause());
    assertTrue(ex.errorCode().isPresent());
    assertSame(SampleCode.BOOM, ex.errorCode().get());
  }

  @Test
  void codeMessageAndCause_carriesEverything() {
    Throwable cause = new IllegalStateException("root");
    DomainException ex = new DomainException(SampleCode.BOOM, "boom", cause);

    assertEquals("boom", ex.getMessage());
    assertSame(cause, ex.getCause());
    assertSame(SampleCode.BOOM, ex.errorCode().get());
  }
}
