package com.example.payment.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * The published language validates at construction: a payload that cannot honour the contract is
 * refused at parse time, so the consuming bridge classifies it as poison (Jackson surfaces the
 * refusal as a {@code ValueInstantiationException}, a {@code JsonProcessingException}, already on
 * the not-retryable list) instead of NPE-ing deep in a handler after a futile retry round. The full
 * classification argument lives with {@code ordering-api}'s test of the same name.
 */
class ContractValidationTest {

  @Test
  void paymentAuthorizedRefusesABlankOrderId() {
    assertThrows(IllegalArgumentException.class, () -> new PaymentAuthorized(null));
    assertThrows(IllegalArgumentException.class, () -> new PaymentAuthorized(" "));
  }

  @Test
  void paymentDeclinedRefusesABlankOrderIdOrCode() {
    assertThrows(
        IllegalArgumentException.class, () -> new PaymentDeclined(null, "payment.limit", "over"));
    assertThrows(IllegalArgumentException.class, () -> new PaymentDeclined("o-1", null, "over"));
    assertThrows(IllegalArgumentException.class, () -> new PaymentDeclined("o-1", " ", "over"));
  }

  @Test
  void paymentDeclinedToleratesAMissingReason() {
    // The code is the machine identity consumers branch on and are entitled to reject without
    // (ordering's evidence types refuse a blank reasonCode); the human-readable reason is detail,
    // and consumers demonstrably accept its absence — refusing it would dead-letter messages
    // every consumer could handle.
    assertDoesNotThrow(() -> new PaymentDeclined("o-1", "payment.limit", null));
  }
}
