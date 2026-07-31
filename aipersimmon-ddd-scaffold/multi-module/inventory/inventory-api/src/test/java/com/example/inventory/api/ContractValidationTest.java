package com.example.inventory.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The published language validates at construction (issue-00143): a payload that cannot honour the
 * contract is refused at parse time, so the consuming bridge classifies it as poison (Jackson
 * surfaces the refusal as a {@code ValueInstantiationException}, a {@code JsonProcessingException},
 * already on the not-retryable list) instead of NPE-ing deep in a handler after a futile retry
 * round. The full classification argument lives with {@code ordering-api}'s test of the same name.
 */
class ContractValidationTest {

  @Test
  void stockReservedRefusesBlankIdentifiers() {
    assertThrows(IllegalArgumentException.class, () -> new StockReserved(null, "res-1"));
    assertThrows(IllegalArgumentException.class, () -> new StockReserved("o-1", " "));
  }

  @Test
  void stockReleasedRefusesBlankIdentifiers() {
    assertThrows(IllegalArgumentException.class, () -> new StockReleased(" ", "res-1"));
    assertThrows(IllegalArgumentException.class, () -> new StockReleased("o-1", null));
  }

  @Test
  void stockReservationFailedRefusesABlankOrderIdOrCode() {
    // "code is never null" was already this event's documented guarantee, held on the producing
    // side by a test; now the type itself holds it, on every path.
    assertThrows(
        IllegalArgumentException.class, () -> new StockReservationFailed(null, "x", "reason"));
    assertThrows(
        IllegalArgumentException.class, () -> new StockReservationFailed("o-1", null, "reason"));
    assertThrows(
        IllegalArgumentException.class, () -> new StockReservationFailed("o-1", " ", "reason"));
  }

  @Test
  void stockReservationFailedToleratesAMissingReason() {
    // The code is the machine identity; the human-readable reason is detail, and the consuming
    // evidence type (ordering's ReservationFailureRef) demonstrably accepts a null detail.
    assertDoesNotThrow(() -> new StockReservationFailed("o-1", "inventory.unspecified", null));
  }

  @Test
  void stockQueryRefusesNullLinesBlankSkusAndNonPositiveQuantities() {
    assertThrows(IllegalArgumentException.class, () -> new StockQuery(null));
    assertThrows(IllegalArgumentException.class, () -> new StockQuery.Line(" ", 1));
    // The quantity is the point of the line (issue-00150): zero of something is not a question.
    assertThrows(IllegalArgumentException.class, () -> new StockQuery.Line("SKU-1", 0));
  }

  @Test
  void stockAvailabilityReportRefusesNullItemsAndBlankSkus() {
    assertThrows(IllegalArgumentException.class, () -> new StockAvailabilityReport(null));
    assertThrows(IllegalArgumentException.class, () -> new StockAvailabilityReport.Item(" ", true));
  }

  @Test
  void anEmptyQueryIsLegal() {
    // A degenerate but well-defined request with a well-defined answer; refusing it would invent
    // a failure mode for callers that validly have nothing to ask.
    assertDoesNotThrow(() -> new StockQuery(List.of()));
    assertDoesNotThrow(() -> new StockAvailabilityReport(List.of()));
  }
}
