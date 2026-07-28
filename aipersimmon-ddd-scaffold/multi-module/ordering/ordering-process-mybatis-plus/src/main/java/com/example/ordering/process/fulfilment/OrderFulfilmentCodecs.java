package com.example.ordering.process.fulfilment;

import com.aipersimmon.ddd.processmanager.codec.EncodedPayload;
import com.aipersimmon.ddd.processmanager.codec.PayloadType;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodec;
import com.aipersimmon.ddd.processmanager.engine.autoconfigure.codec.ProcessSerializationCatalog;
import com.aipersimmon.ddd.processmanager.exception.ProcessSerializationException;
import com.aipersimmon.ddd.processmanager.model.StateSchemaVersion;
import com.example.ordering.application.order.BeginFulfilment;
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
import com.example.ordering.process.fulfilment.OrderFulfilmentInput.StockReleaseTimedOut;
import com.example.ordering.process.fulfilment.OrderFulfilmentInput.StockReleased;
import com.example.ordering.process.fulfilment.OrderFulfilmentInput.StockReservationFailed;
import com.example.ordering.process.fulfilment.OrderFulfilmentInput.StockReservationTimedOut;
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

  /**
   * The field separator of this codec's wire format: ASCII 0x1F (unit separator).
   *
   * <p>Written as an escape, never as a raw byte. A raw 0x1F is invisible in an editor and in a
   * diff, and this constant defines a <em>persisted</em> format — changing it silently would leave
   * already-stored rows undecodable (issue-00087).
   *
   * <p>It cannot occur in an id or an enum name, so those fields need no escaping. The one field
   * that is free text — a reservation failure's {@code detail}, carrying the inventory context's
   * message verbatim — could contain it, and is handled by {@link #decodeCancel} bounding its split
   * so that field absorbs the remainder rather than shifting the ones after it. Fields are
   * therefore still written unescaped, which is what keeps the format unchanged; the constraint
   * this buys is that a free-text field must be last in its variant.
   */
  private static final String US = "\u001F";

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
        .payload(
            "ordering.fulfilment.stock-reservation-timed-out", 1, StockReservationTimedOut.class)
        .payload("ordering.fulfilment.payment-authorized", 1, PaymentAuthorized.class)
        .payload("ordering.fulfilment.payment-declined", 1, PaymentDeclined.class)
        // The flow's own timers, encoded like any other input: a deadline is delivered back
        // through handle(), so its payload lives in the same catalog as the facts from other
        // contexts. There are three of them now, one per step that waits on a broker (issue-00068).
        .payload("ordering.fulfilment.payment-timed-out", 1, PaymentTimedOut.class)
        .payload("ordering.fulfilment.stock-released", 1, StockReleased.class)
        .payload("ordering.fulfilment.stock-release-timed-out", 1, StockReleaseTimedOut.class)
        .payload("ordering.fulfilment.order-confirmed", 1, OrderConfirmed.class)
        .payload("ordering.fulfilment.order-cancelled", 1, OrderCancelled.class)
        // command effects — what the flow dispatches (CancelOrder is the exception, below)
        .payload("ordering.fulfilment.request-payment", 1, RequestPayment.class)
        .payload("ordering.fulfilment.begin-fulfilment", 1, BeginFulfilment.class)
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

  /**
   * Reads the fields back by position, with each variant's split bounded to its own field count so
   * the <em>last</em> field takes whatever remains (issue-00087).
   *
   * <p>An unbounded {@code split(US, -1)} read every field positionally, which is only safe while
   * no field value can contain the separator. One can: a reservation failure's {@code detail} is
   * the inventory context's message passed through verbatim. A separator inside it produced one
   * extra field, shifted everything after it, and the positional reads then either threw {@code
   * ArrayIndexOutOfBoundsException} or — worse — built a reason out of values taken from the wrong
   * fields. Decode-time only, long after the causing event is gone.
   *
   * <p>Bounding the split fixes it because {@code detail} is last in its variant, so there is
   * nothing after it to shift. It is worth being clear that this is <strong>not</strong> a wire
   * change, and so needs no version bump: the encoder is untouched, every row that decoded
   * correctly before still decodes to the same command, and the only rows whose meaning changes are
   * the ones that previously decoded to the wrong thing. Escaping the field would have been a wire
   * change — a different, larger fix for the same defect, and it is not needed while the free-text
   * field stays last. If a second free-text field is ever added, or one is added after this one,
   * that larger fix becomes the only option.
   */
  private static CancelOrder decodeCancel(String text) {
    // orderId, kind, and everything else — the variant decides how the rest divides.
    String[] head = text.split(US, 3);
    if (head.length < 3) {
      throw new ProcessSerializationException("malformed cancel-order payload");
    }
    CancellationReason reason =
        switch (head[1]) {
          case "INVENTORY_UNAVAILABLE" -> {
            // failureId, orderId, reasonCode, detail — detail is free text and absorbs the rest.
            String[] fields = head[2].split(US, 4);
            yield new CancellationReason.InventoryUnavailable(
                new ReservationFailureRef(fields[0], new OrderId(fields[1]), fields[2], fields[3]));
          }
          case "PAYMENT_DECLINED" -> {
            // declineId, orderId, declineCode, releaseId, releaseOrderId — all ids and codes,
            // none of which can carry a separator, but bounded for the same reason regardless.
            String[] fields = head[2].split(US, 5);
            yield new CancellationReason.PaymentDeclinedAfterStockReleased(
                new PaymentDeclineRef(fields[0], new OrderId(fields[1]), fields[2]),
                new StockReleaseRef(fields[3], new OrderId(fields[4])));
          }
          default ->
              throw new ProcessSerializationException("unknown cancel reason kind: " + head[1]);
        };
    return new CancelOrder(head[0], reason);
  }
}
