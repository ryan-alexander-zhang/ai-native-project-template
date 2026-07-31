package com.example.ordering.process.fulfilment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.processmanager.codec.EncodedPayload;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodec;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.engine.autoconfigure.codec.JacksonProcessCodecConfiguration;
import com.aipersimmon.ddd.processmanager.exception.ProcessSerializationException;
import com.example.ordering.application.order.CancelOrder;
import com.example.ordering.domain.customer.CustomerId;
import com.example.ordering.domain.order.CancellationReason;
import com.example.ordering.domain.order.OrderId;
import com.example.ordering.domain.order.PaymentDeclineRef;
import com.example.ordering.domain.order.ReservationFailureRef;
import com.example.ordering.domain.order.ReviewDecisionRef;
import com.example.ordering.domain.order.StockReleaseRef;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The {@code CancelOrder} payload's persisted format, pinned by round trip — through the very
 * codecs the framework's Jackson layer generates from {@link OrderFulfilmentCodecs}' catalog, mixin
 * included, so what is tested is what the deployment runs.
 *
 * <p>This file used to specify a hand-written unit-separator format and its two parsing traps
 * (issue-00087). The mix-in route (issue-00136) deleted that codec: free text in {@code detail} is
 * now just a JSON string — no separator can shift fields — and "which variant is this" is a
 * declared discriminator instead of a positional convention. What still deserves pinning is the
 * discriminator itself (wire contract: renaming a class must not change it) and the malformed-row
 * refusal.
 *
 * <p>Kept apart from {@code OrderFulfilmentDefinitionTest}, which tests the pure transition table
 * and says so — this is about a persisted format, a different subject with a different reason to
 * change.
 */
class OrderFulfilmentCodecsTest {

  private final ObjectMapper applicationMapper = new ObjectMapper();
  private final ProcessPayloadCodecRegistry registry =
      new JacksonProcessCodecConfiguration()
          .processPayloadCodecRegistry(
              noExplicitCodecs(),
              new OrderFulfilmentCodecs().orderFulfilmentSerialization(),
              applicationMapper);
  private final ProcessPayloadCodec<CancelOrder> codec = registry.forJavaType(CancelOrder.class);

  @Test
  void anInventoryUnavailableCancellationRoundTrips() {
    CancelOrder command = cancelledBecauseStockWasShort("SKU-1: asked 999, available 10");

    assertEquals(command, codec.decode(codec.encode(command)));
  }

  @Test
  void freeTextInTheDetailCannotShiftAnything() {
    // The old positional format's failure mode (issue-00087): a 0x1F inside inventory's verbatim
    // message produced one extra field. JSON has no positional fields to shift; kept as the
    // regression witness for the same input.
    CancelOrder command = cancelledBecauseStockWasShort("asked 999\u001Favailable 10");

    assertEquals(command, codec.decode(codec.encode(command)));
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
  void theVariantsThisFlowNeverDispatchesRoundTripToo() {
    // The codec encodes the type, not the flow's habits: which reasons may be dispatched is the
    // definition's business, enforced where reasons are constructed. The framework's sealed-
    // coverage check made these two mappings mandatory; this pins that they actually work.
    CancelOrder byCustomer =
        new CancelOrder(
            "order-3", new CancellationReason.CustomerRequested(new CustomerId("cust-7")));
    CancelOrder byReview =
        new CancelOrder(
            "order-4",
            new CancellationReason.ReviewRejected(
                new ReviewDecisionRef.Rejection("review-9", new OrderId("order-4"))));

    assertEquals(byCustomer, codec.decode(codec.encode(byCustomer)));
    assertEquals(byReview, codec.decode(codec.encode(byReview)));
  }

  @Test
  void theWireCarriesTheDeclaredDiscriminatorNotTheClassName() {
    String json =
        new String(
            codec.encode(cancelledBecauseStockWasShort("out of stock")).data(),
            StandardCharsets.UTF_8);

    // The discriminator is wire contract, exactly like the catalog's logical type: a persisted
    // effect must survive the variant class being renamed or moved.
    assertTrue(json.contains("\"INVENTORY_UNAVAILABLE\""), json);
    assertTrue(!json.contains("InventoryUnavailable"), json);
  }

  @Test
  void aMalformedPayloadIsRejectedAsUnserializable() {
    assertThrows(
        ProcessSerializationException.class,
        () ->
            codec.decode(
                new EncodedPayload(
                    codec.payloadType(), "not json".getBytes(StandardCharsets.UTF_8))));
  }

  @Test
  void anUnknownReasonKindIsRejected() {
    assertThrows(
        ProcessSerializationException.class,
        () ->
            codec.decode(
                new EncodedPayload(
                    codec.payloadType(),
                    "{\"orderId\":\"order-1\",\"reason\":{\"kind\":\"SOMETHING_ELSE\"}}"
                        .getBytes(StandardCharsets.UTF_8))));
  }

  private static CancelOrder cancelledBecauseStockWasShort(String detail) {
    return new CancelOrder(
        "order-1",
        new CancellationReason.InventoryUnavailable(
            new ReservationFailureRef(
                "failure-1", new OrderId("order-1"), "inventory.insufficient-stock", detail)));
  }

  private static ObjectProvider<ProcessPayloadCodec<?>> noExplicitCodecs() {
    return new ObjectProvider<>() {
      @Override
      public ProcessPayloadCodec<?> getObject() {
        throw new UnsupportedOperationException();
      }

      @Override
      public Stream<ProcessPayloadCodec<?>> orderedStream() {
        return Stream.empty();
      }
    };
  }
}
