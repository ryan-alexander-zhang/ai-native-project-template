package com.example.ordering.process.fulfilment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.processmanager.codec.EncodedPayload;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodec;
import com.aipersimmon.ddd.processmanager.exception.ProcessSerializationException;
import com.example.ordering.application.order.CancelOrder;
import com.example.ordering.domain.order.CancellationReason;
import com.example.ordering.domain.order.OrderId;
import com.example.ordering.domain.order.PaymentDeclineRef;
import com.example.ordering.domain.order.ReservationFailureRef;
import com.example.ordering.domain.order.StockReleaseRef;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The hand-written {@code CancelOrder} codec's wire format, pinned by round trip (issue-00087).
 *
 * <p>The case that matters is a {@code detail} containing the separator itself. That field is free
 * text — inventory's failure message, passed through verbatim — so nothing prevents it, and the old
 * unbounded {@code split} then read every following field one position out. The damage surfaced at
 * decode time, in a relay, long after the event that produced the string; the outcome was either an
 * index out of bounds or a cancellation reason quietly assembled from the wrong values.
 *
 * <p>Kept apart from {@code OrderFulfilmentDefinitionTest}, which tests the pure transition table
 * and says so — this is about a persisted format, a different subject with a different reason to
 * change.
 */
class OrderFulfilmentCodecsTest {

  /**
   * The codec's separator, written as an escape here for the same reason it is written as one
   * there: a raw 0x1F in a test source is invisible, and a test whose input cannot be read is not
   * much of a specification.
   */
  private static final String SEPARATOR = "\u001F";

  private final ProcessPayloadCodec<CancelOrder> codec =
      new OrderFulfilmentCodecs().cancelOrderCodec();

  @Test
  void anInventoryUnavailableCancellationRoundTrips() {
    CancelOrder command = cancelledBecauseStockWasShort("SKU-1: asked 999, available 10");

    assertEquals(command, codec.decode(codec.encode(command)));
  }

  @Test
  void aDetailContainingTheSeparatorRoundTripsInsteadOfShiftingTheFields() {
    CancelOrder command = cancelledBecauseStockWasShort("asked 999" + SEPARATOR + "available 10");

    assertEquals(
        command,
        codec.decode(codec.encode(command)),
        "detail is the last field of its variant, so the remainder belongs to it");
  }

  @Test
  void aPaymentDeclinedCancellationRoundTrips() {
    CancelOrder command =
        new CancelOrder(
            "order-2",
            new CancellationReason.PaymentDeclinedAfterStockReleased(
                new PaymentDeclineRef(
                    "decline-1", new OrderId("order-2"), "payment.amount-exceeds-ceiling"),
                new StockReleaseRef("release-1", new OrderId("order-2"))));

    assertEquals(command, codec.decode(codec.encode(command)));
  }

  @Test
  void aPayloadWithTooFewFieldsIsRejectedAsMalformed() {
    assertThrows(
        ProcessSerializationException.class,
        () -> decode("order-1"),
        "a truncated payload must name itself as malformed, not fail on an array index");
  }

  @Test
  void anUnknownReasonKindIsRejected() {
    assertThrows(
        ProcessSerializationException.class,
        () -> decode("order-1" + SEPARATOR + "SOMETHING_ELSE" + SEPARATOR + "x"));
  }

  private static CancelOrder cancelledBecauseStockWasShort(String detail) {
    return new CancelOrder(
        "order-1",
        new CancellationReason.InventoryUnavailable(
            new ReservationFailureRef(
                "failure-1", new OrderId("order-1"), "inventory.insufficient-stock", detail)));
  }

  private CancelOrder decode(String wire) {
    return codec.decode(
        new EncodedPayload(codec.payloadType(), wire.getBytes(StandardCharsets.UTF_8)));
  }
}
