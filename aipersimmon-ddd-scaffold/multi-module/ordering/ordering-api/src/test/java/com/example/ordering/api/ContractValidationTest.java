package com.example.ordering.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The published language validates at construction (issue-00143): parse, don't validate. The
 * consuming bridge already checks every {@code ce_*} header strictly and dead-letters a malformed
 * one at once — but an empty-object payload used to deserialize successfully and carry its nulls
 * all the way into a handler, where the NPE was classified as ambiguous and retried pointlessly
 * before dead-lettering. With the compact constructors below, Jackson surfaces the refusal as a
 * {@link ValueInstantiationException} — a {@link JsonProcessingException}, which the consumer's
 * error handler already classifies as poison — so a bad payload is dead-lettered exactly like a bad
 * header: immediately, and naming the field that broke the contract.
 */
class ContractValidationTest {

  private static final Instant DEADLINE = Instant.parse("2026-07-28T12:01:00Z");
  private static final List<OrderReadyForFulfilment.Line> LINES =
      List.of(new OrderReadyForFulfilment.Line("SKU-1", 2));

  // ---- OrderReadyForFulfilment (v2) ----

  @Test
  void readyForFulfilmentRefusesAMissingOrderId() {
    assertThrows(
        IllegalArgumentException.class, () -> new OrderReadyForFulfilment(null, LINES, DEADLINE));
    assertThrows(
        IllegalArgumentException.class, () -> new OrderReadyForFulfilment(" ", LINES, DEADLINE));
  }

  @Test
  void readyForFulfilmentRefusesMissingOrEmptyLines() {
    assertThrows(
        IllegalArgumentException.class, () -> new OrderReadyForFulfilment("o-1", null, DEADLINE));
    assertThrows(
        IllegalArgumentException.class,
        () -> new OrderReadyForFulfilment("o-1", List.of(), DEADLINE));
  }

  @Test
  void readyForFulfilmentToleratesAMissingDeadline() {
    // reservationDeadline is v2's addition; a consumer must cope without one (the V1 upcast has
    // none to give), so the contract cannot demand it.
    assertDoesNotThrow(() -> new OrderReadyForFulfilment("o-1", LINES, null));
  }

  @Test
  void aLineRefusesABlankSkuAndANonPositiveQuantity() {
    assertThrows(IllegalArgumentException.class, () -> new OrderReadyForFulfilment.Line(" ", 1));
    assertThrows(
        IllegalArgumentException.class, () -> new OrderReadyForFulfilment.Line("SKU-1", 0));
    assertThrows(
        IllegalArgumentException.class, () -> new OrderReadyForFulfilment.Line("SKU-1", -1));
  }

  // ---- OrderReadyForFulfilmentV1 (frozen revision, still read from the wire) ----

  @Test
  void theFrozenRevisionValidatesTheSameWay() {
    // V1 exists to be read: old messages deserialize into it, so a poison v1 payload must be
    // refused at parse time exactly like a v2 one.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new OrderReadyForFulfilmentV1(
                null, List.of(new OrderReadyForFulfilmentV1.Line("SKU-1", 1))));
    assertThrows(
        IllegalArgumentException.class, () -> new OrderReadyForFulfilmentV1("o-1", List.of()));
    assertThrows(
        IllegalArgumentException.class, () -> new OrderReadyForFulfilmentV1.Line("SKU-1", 0));
  }

  // ---- PaymentRequested ----

  @Test
  void paymentRequestedRefusesBlankIdentifiers() {
    assertThrows(
        IllegalArgumentException.class, () -> new PaymentRequested(null, "op-1", 100, "USD"));
    assertThrows(
        IllegalArgumentException.class, () -> new PaymentRequested("o-1", " ", 100, "USD"));
    assertThrows(
        IllegalArgumentException.class, () -> new PaymentRequested("o-1", "op-1", 100, null));
  }

  @Test
  void paymentRequestedCarriesZeroButRefusesANegativeAmount() {
    // The issue-00075 range, finally written as code: zero is a legal amount (a fully discounted
    // basket), negative is not an amount at all.
    assertDoesNotThrow(() -> new PaymentRequested("o-1", "op-1", 0, "USD"));
    assertThrows(
        IllegalArgumentException.class, () -> new PaymentRequested("o-1", "op-1", -1, "USD"));
  }

  // ---- StockReleaseRequested ----

  @Test
  void stockReleaseRequestedRefusesBlankIdentifiers() {
    assertThrows(IllegalArgumentException.class, () -> new StockReleaseRequested(null, "res-1"));
    assertThrows(IllegalArgumentException.class, () -> new StockReleaseRequested("o-1", " "));
  }

  // ---- The linchpin: how a refusal reaches the consumer's error handler ----

  @Test
  void anEmptyObjectPayloadFailsAtParseTimeAsAJsonProcessingException() throws Exception {
    // This is the classification the whole fix rides on. The consuming bridge deserializes with
    // Jackson; a compact-constructor refusal surfaces as ValueInstantiationException, which IS a
    // JsonProcessingException — already on the error handler's not-retryable list. So {} is
    // dead-lettered at once as the poison it is, instead of NPE-ing deep in a handler and
    // burning a retry budget first.
    ObjectMapper mapper = new ObjectMapper();

    ValueInstantiationException refused =
        assertThrows(
            ValueInstantiationException.class,
            () -> mapper.readValue("{}", OrderReadyForFulfilment.class));

    assertInstanceOf(
        JsonProcessingException.class,
        refused,
        "the refusal must be a JsonProcessingException, or the consumer would retry it");
  }
}
