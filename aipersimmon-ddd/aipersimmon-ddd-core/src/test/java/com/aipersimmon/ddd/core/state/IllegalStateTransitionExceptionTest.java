package com.aipersimmon.ddd.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.core.error.ErrorCode;
import org.junit.jupiter.api.Test;

class IllegalStateTransitionExceptionTest {

  private enum SampleCode implements ErrorCode {
    ILLEGAL;

    @Override
    public String code() {
      return "sample.illegal-transition";
    }
  }

  @Test
  void withoutCode_formatsFromAndToAndCarriesNoCode() {
    IllegalStateTransitionException ex = new IllegalStateTransitionException("NEW", "SHIPPED");

    assertEquals("illegal state transition: NEW -> SHIPPED", ex.getMessage());
    assertTrue(ex.errorCode().isEmpty());
  }

  @Test
  void withCode_formatsMessageAndCarriesCode() {
    IllegalStateTransitionException ex =
        new IllegalStateTransitionException(SampleCode.ILLEGAL, "NEW", "SHIPPED");

    assertEquals("illegal state transition: NEW -> SHIPPED", ex.getMessage());
    assertTrue(ex.errorCode().isPresent());
    assertSame(SampleCode.ILLEGAL, ex.errorCode().get());
  }
}
