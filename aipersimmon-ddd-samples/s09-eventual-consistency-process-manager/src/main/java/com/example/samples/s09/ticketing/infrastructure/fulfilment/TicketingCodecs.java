package com.example.samples.s09.ticketing.infrastructure.fulfilment;

import com.aipersimmon.ddd.processmanager.engine.autoconfigure.codec.ProcessSerializationCatalog;
import com.aipersimmon.ddd.processmanager.model.StateSchemaVersion;
import com.example.samples.s09.ticketing.application.CancelTicketOrder;
import com.example.samples.s09.ticketing.application.ChargeWallet;
import com.example.samples.s09.ticketing.application.HoldSeat;
import com.example.samples.s09.ticketing.application.IssueTicket;
import com.example.samples.s09.ticketing.application.RefundWallet;
import com.example.samples.s09.ticketing.application.ReleaseSeat;
import com.example.samples.s09.ticketing.application.fulfilment.TicketingDefinition;
import com.example.samples.s09.ticketing.application.fulfilment.TicketingInput;
import com.example.samples.s09.ticketing.application.fulfilment.TicketingState;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * How this flow's persisted values are encoded — nineteen lines of registration, and the catalogue asks
 * why they cannot be inferred.
 *
 * <p><strong>Because a class name is not a persistence contract.</strong> Every entry names a stable
 * logical type and version; the Java class is only the current carrier. Rename {@code HoldSeat}, move it
 * to another package, and the rows written last week still decode — a flow instance can easily outlive
 * three refactorings, since it is a row waiting for something that has not happened yet. Reflection over
 * class names would make every rename a data migration, silently.
 *
 * <p><strong>And because a forgotten registration must not be discovered at the worst moment.</strong>
 * The definition declares its payload classes ({@code declaredPayloads}) and the startup validator
 * reconciles them against this catalog, so a missing entry fails the boot. Without that pairing, the
 * failure surfaces as an encode error inside a transaction, on the first order that happens to reach the
 * unregistered step — which for a compensating branch may be weeks after the deploy that broke it.
 *
 * <p>The version numbers are all 1 because nothing here has been through a schema change. What makes them
 * load-bearing later: re-encoding an existing {@code (type, version)} differently leaves already-persisted
 * rows undecodable, so a format change means a new version and keeping the old codec until no row carries
 * it. That is the same discipline as S21's event revisions, applied to a flow's own storage.
 *
 * <p>Hand-writing a {@code ProcessPayloadCodec} bean stays the escape hatch, and the occasions are narrow:
 * encryption at rest, upcasting an old version on decode, or a non-JSON format imposed from outside.
 */
@Configuration(proxyBeanMethods = false)
class TicketingCodecs {

  @Bean
  ProcessSerializationCatalog ticketingSerialization() {
    return ProcessSerializationCatalog.builder()
        // the facts the flow reacts to, including its own two timers
        .payload("ticketing.fulfilment.order-placed", 1, TicketingInput.OrderPlaced.class)
        .payload("ticketing.fulfilment.seat-held", 1, TicketingInput.SeatHeld.class)
        .payload("ticketing.fulfilment.seat-sold-out", 1, TicketingInput.SeatSoldOut.class)
        .payload("ticketing.fulfilment.seat-wait-timed-out", 1, TicketingInput.SeatWaitTimedOut.class)
        .payload("ticketing.fulfilment.wallet-charged", 1, TicketingInput.WalletCharged.class)
        .payload("ticketing.fulfilment.wallet-declined", 1, TicketingInput.WalletDeclined.class)
        .payload(
            "ticketing.fulfilment.payment-wait-timed-out",
            1,
            TicketingInput.PaymentWaitTimedOut.class)
        .payload("ticketing.fulfilment.wallet-refunded", 1, TicketingInput.WalletRefunded.class)
        .payload("ticketing.fulfilment.seat-released", 1, TicketingInput.SeatReleased.class)
        .payload("ticketing.fulfilment.ticket-issued", 1, TicketingInput.TicketIssued.class)
        .payload("ticketing.fulfilment.order-cancelled", 1, TicketingInput.OrderCancelled.class)
        .payload(
            "ticketing.fulfilment.cancellation-requested",
            1,
            TicketingInput.CancellationRequested.class)
        // the commands the flow stages as effects
        .payload("ticketing.fulfilment.hold-seat", 1, HoldSeat.class)
        .payload("ticketing.fulfilment.release-seat", 1, ReleaseSeat.class)
        .payload("ticketing.fulfilment.charge-wallet", 1, ChargeWallet.class)
        .payload("ticketing.fulfilment.refund-wallet", 1, RefundWallet.class)
        .payload("ticketing.fulfilment.issue-ticket", 1, IssueTicket.class)
        .payload("ticketing.fulfilment.cancel-ticket-order", 1, CancelTicketOrder.class)
        // and the flow's own state
        .state(
            TicketingDefinition.PROCESS_TYPE,
            new StateSchemaVersion(1),
            "ticketing.fulfilment.state",
            TicketingState.class)
        .build();
  }
}
