package com.example.ordering.process.fulfilment;

import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodec;
import com.aipersimmon.ddd.processmanager.engine.autoconfigure.codec.ProcessSerializationCatalog;
import com.aipersimmon.ddd.processmanager.model.StateSchemaVersion;
import com.example.ordering.application.order.BeginFulfilment;
import com.example.ordering.application.order.CancelOrder;
import com.example.ordering.application.order.ConfirmOrder;
import com.example.ordering.application.order.RequestPayment;
import com.example.ordering.application.order.RequestStockRelease;
import com.example.ordering.domain.order.CancellationReason;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * How the order-fulfilment flow's persisted values are encoded. Every entry names a <em>stable
 * logical type and version</em> — never a Java class name — so a payload written by one deployment
 * stays readable after the class is renamed or moved.
 *
 * <p>Everything goes through the default route, the {@link ProcessSerializationCatalog}: declare
 * the logical type, version and Java type; the framework's Jackson layer generates the codec.
 *
 * <p>{@link CancelOrder} deserves a note, because it used to be a hand-written codec. It carries a
 * {@link CancellationReason} — a sealed interface whose variants Jackson must tell apart — and
 * putting {@code @JsonTypeInfo} on a domain type is exactly the infrastructure leak the layering
 * forbids. That looked like a two-way choice between annotating the domain and hand-writing the
 * codec; it was not. The catalog's {@code mixIn} registration puts the polymorphism declaration on
 * {@link CancellationReasonMixIn} here in this module, applied only to the codecs' private mapper.
 * The hand-written codec it replaces carried two latent parsing bugs (unvalidated positional
 * splits, {@code null} fields written as the literal string {@code "null"}) and a maintenance
 * contract nothing checked — precisely the trade the framework's Jackson layer exists to take away
 * (issue-00136).
 *
 * <p>Hand-writing a {@link ProcessPayloadCodec} bean remains the escape hatch, but the real
 * occasions are now narrower: <strong>encryption</strong> of a payload at rest,
 * <strong>upcasting</strong> an old version forward on decode, or <strong>a non-JSON
 * format</strong> imposed from outside. The registries fail fast if a hand-written codec and a
 * catalog entry claim the same logical type/version.
 *
 * <p><strong>Changing an encoding is a wire change.</strong> The logical type/version identifies
 * the <em>format</em>, not just the shape. Re-encoding an existing {@code (type, version)}
 * differently would leave already-persisted rows undecodable, so in production you bump the version
 * and keep a codec for the old one until no rows carry it. (Replacing the hand-written cancel-order
 * codec with JSON under the same version was exactly such a change, taken deliberately: this
 * project is pre-production, and no deployed row carries the old format.)
 */
@Configuration
public class OrderFulfilmentCodecs {

  /**
   * One line per payload: logical type, version, Java type. Version 1 for all of them because none
   * has been through a schema change yet. The {@code mixIn} line is what lets {@link CancelOrder}
   * ride the default route despite its sealed payload — see the class javadoc.
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
        // command effects — what the flow dispatches
        .payload("ordering.fulfilment.request-payment", 1, RequestPayment.class)
        .payload("ordering.fulfilment.begin-fulfilment", 1, BeginFulfilment.class)
        .payload("ordering.fulfilment.confirm-order", 1, ConfirmOrder.class)
        .payload("ordering.fulfilment.request-stock-release", 1, RequestStockRelease.class)
        .payload("ordering.fulfilment.cancel-order", 1, CancelOrder.class)
        .mixIn(CancellationReason.class, CancellationReasonMixIn.class)
        // the flow's own state
        .state(
            OrderFulfilmentDefinition.PROCESS_TYPE,
            new StateSchemaVersion(1),
            "ordering.fulfilment.state",
            OrderFulfilmentState.class)
        .build();
  }
}
