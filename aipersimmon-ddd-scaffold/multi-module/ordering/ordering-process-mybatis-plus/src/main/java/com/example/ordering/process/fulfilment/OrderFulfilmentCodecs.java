package com.example.ordering.process.fulfilment;

import com.aipersimmon.ddd.processmanager.codec.EncodedPayload;
import com.aipersimmon.ddd.processmanager.codec.PayloadType;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodec;
import com.aipersimmon.ddd.processmanager.engine.autoconfigure.codec.ProcessSerializationCatalog;
import com.aipersimmon.ddd.processmanager.exception.ProcessSerializationException;
import com.aipersimmon.ddd.processmanager.model.StateSchemaVersion;
import com.example.ordering.application.order.CancelOrder;
import com.example.ordering.application.order.ConfirmOrder;
import com.example.ordering.application.order.RequestPayment;
import com.example.ordering.application.order.RequestStockRelease;
import com.example.ordering.domain.order.CancellationReason;
import com.example.ordering.domain.order.OrderId;
import com.example.ordering.domain.order.PaymentDeclineRef;
import com.example.ordering.domain.order.ReservationFailureRef;
import com.example.ordering.domain.order.StockReleaseRef;
import com.example.ordering.process.fulfilment.OrderFulfilmentInput.OrderCancelled;
import com.example.ordering.process.fulfilment.OrderFulfilmentInput.OrderConfirmed;
import com.example.ordering.process.fulfilment.OrderFulfilmentInput.PaymentAuthorized;
import com.example.ordering.process.fulfilment.OrderFulfilmentInput.PaymentDeclined;
import com.example.ordering.process.fulfilment.OrderFulfilmentInput.PaymentTimedOut;
import com.example.ordering.process.fulfilment.OrderFulfilmentInput.ReadyForFulfilment;
import com.example.ordering.process.fulfilment.OrderFulfilmentInput.StockReleased;
import com.example.ordering.process.fulfilment.OrderFulfilmentInput.StockReservationFailed;
import com.example.ordering.process.fulfilment.OrderFulfilmentInput.StockReserved;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * How the order-fulfilment flow's persisted values are encoded. Every entry names a <em>stable
 * logical type and version</em> — never a Java class name — so a payload written by one deployment
 * stays readable after the class is renamed or moved.
 *
 * <p>It shows both routes, because a real flow usually needs both:
 *
 * <ol>
 *   <li><strong>The default route: {@link ProcessSerializationCatalog}.</strong> Declare the
 *       logical type, version and Java type; the framework's Jackson layer generates the codec.
 *       Twelve of this flow's thirteen payloads are records of strings and one enum, so that is all
 *       they need.
 *   <li><strong>The escape hatch: an explicit {@link ProcessPayloadCodec} bean.</strong> {@link
 *       CancelOrder} needs one, and the reason is instructive — see below.
 * </ol>
 *
 * <p>The two compose: the framework builds its registries from the catalog <em>plus</em> whatever
 * codec beans exist, and fails fast if the same logical type/version is claimed twice.
 *
 * <h2>When you actually need to hand-write one</h2>
 *
 * Only when Jackson cannot or must not do it:
 *
 * <ul>
 *   <li><strong>A polymorphic payload whose type must stay annotation-free</strong> — this flow's
 *       case. {@code CancelOrder} carries a {@link CancellationReason}, a sealed interface with two
 *       record variants. Jackson would need {@code @JsonTypeInfo} on it to know which variant to
 *       rebuild, and {@code CancellationReason} lives in {@code ordering-domain}: putting a
 *       serialization annotation on a domain type is exactly the infrastructure leak the layering
 *       forbids. Writing the discriminator by hand keeps the domain clean.
 *   <li><strong>Encryption</strong> of a payload at rest.
 *   <li><strong>Upcasting</strong> — reading an old version and migrating it forward on decode.
 *   <li><strong>A non-JSON format</strong> imposed from outside.
 * </ul>
 *
 * <p>If none of those apply, use the catalog. A hand-written codec has to be kept in step with the
 * type it encodes, and nothing checks that for you.
 *
 * <p><strong>Changing an encoding is a wire change.</strong> The logical type/version identifies
 * the <em>format</em>, not just the shape. Re-encoding an existing {@code (type, version)}
 * differently would leave already-persisted rows undecodable, so in production you bump the version
 * and keep a codec for the old one until no rows carry it.
 */
@Configuration
public class OrderFulfilmentCodecs {

  private static final String US = "";

  /**
   * The default route. One line per payload: logical type, version, Java type. Version 1 for all of
   * them because none has been through a schema change yet.
   */
  @Bean
  ProcessSerializationCatalog orderFulfilmentSerialization() {
    return ProcessSerializationCatalog.builder()
        // inputs — the facts the flow reacts to
        .payload("ordering.fulfilment.ready-for-fulfilment", 1, ReadyForFulfilment.class)
        .payload("ordering.fulfilment.stock-reserved", 1, StockReserved.class)
        .payload("ordering.fulfilment.stock-reservation-failed", 1, StockReservationFailed.class)
        .payload("ordering.fulfilment.payment-authorized", 1, PaymentAuthorized.class)
        .payload("ordering.fulfilment.payment-declined", 1, PaymentDeclined.class)
        // The flow's own timer, encoded like any other input: a deadline is delivered back through
        // handle(), so its payload lives in the same catalog as the facts from other contexts.
        .payload("ordering.fulfilment.payment-timed-out", 1, PaymentTimedOut.class)
        .payload("ordering.fulfilment.stock-released", 1, StockReleased.class)
        .payload("ordering.fulfilment.order-confirmed", 1, OrderConfirmed.class)
        .payload("ordering.fulfilment.order-cancelled", 1, OrderCancelled.class)
        // command effects — what the flow dispatches (CancelOrder is the exception, below)
        .payload("ordering.fulfilment.request-payment", 1, RequestPayment.class)
        .payload("ordering.fulfilment.confirm-order", 1, ConfirmOrder.class)
        .payload("ordering.fulfilment.request-stock-release", 1, RequestStockRelease.class)
        // the flow's own state
        .state(
            OrderFulfilmentDefinition.PROCESS_TYPE,
            new StateSchemaVersion(1),
            "ordering.fulfilment.state",
            OrderFulfilmentState.class)
        .build();
  }

  /**
   * The escape hatch, and the one payload that earns it: {@link CancellationReason} is a sealed
   * interface, so decoding has to know which variant to rebuild. The discriminator ({@code
   * INVENTORY_UNAVAILABLE} / {@code PAYMENT_DECLINED}) is written here rather than as a
   * {@code @JsonTypeInfo} annotation on the domain type, so {@code ordering-domain} stays free of
   * serialization concerns. Fields are unit-separator delimited UTF-8 and read back by position.
   *
   * <p>The {@code default} branch is not defensive padding: the ordering aggregate accepts other
   * cancellation reasons, but this flow never dispatches them, and silently encoding one would
   * persist an effect the flow cannot have produced.
   */
  @Bean
  ProcessPayloadCodec<CancelOrder> cancelOrderCodec() {
    return new ProcessPayloadCodec<>() {
      @Override
      public PayloadType payloadType() {
        return new PayloadType("ordering.fulfilment.cancel-order", 1);
      }

      @Override
      public Class<CancelOrder> javaType() {
        return CancelOrder.class;
      }

      @Override
      public EncodedPayload encode(CancelOrder command) {
        return new EncodedPayload(
            payloadType(), encodeCancel(command).getBytes(StandardCharsets.UTF_8));
      }

      @Override
      public CancelOrder decode(EncodedPayload payload) {
        return decodeCancel(new String(payload.data(), StandardCharsets.UTF_8));
      }
    };
  }

  private static String encodeCancel(CancelOrder command) {
    return switch (command.reason()) {
      case CancellationReason.InventoryUnavailable unavailable ->
          String.join(
              US,
              command.orderId(),
              "INVENTORY_UNAVAILABLE",
              unavailable.failure().failureId(),
              unavailable.failure().orderId().value(),
              unavailable.failure().reasonCode(),
              unavailable.failure().detail());
      case CancellationReason.PaymentDeclinedAfterStockReleased declined ->
          String.join(
              US,
              command.orderId(),
              "PAYMENT_DECLINED",
              declined.paymentDecline().declineId(),
              declined.paymentDecline().orderId().value(),
              declined.paymentDecline().declineCode(),
              declined.stockRelease().releaseId(),
              declined.stockRelease().orderId().value());
      default ->
          throw new ProcessSerializationException(
              "order-fulfilment does not dispatch CancelOrder with reason " + command.reason());
    };
  }

  private static CancelOrder decodeCancel(String text) {
    String[] fields = text.split(US, -1);
    CancellationReason reason =
        switch (fields[1]) {
          case "INVENTORY_UNAVAILABLE" ->
              new CancellationReason.InventoryUnavailable(
                  new ReservationFailureRef(
                      fields[2], new OrderId(fields[3]), fields[4], fields[5]));
          case "PAYMENT_DECLINED" ->
              new CancellationReason.PaymentDeclinedAfterStockReleased(
                  new PaymentDeclineRef(fields[2], new OrderId(fields[3]), fields[4]),
                  new StockReleaseRef(fields[5], new OrderId(fields[6])));
          default ->
              throw new ProcessSerializationException("unknown cancel reason kind: " + fields[1]);
        };
    return new CancelOrder(fields[0], reason);
  }
}
