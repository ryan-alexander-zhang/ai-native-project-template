package com.example.ordering.process.fulfilment;

import com.aipersimmon.ddd.processmanager.definition.ProcessInput;

/**
 * The inputs the order-fulfilment process reacts to: the start, the cross-context result facts from
 * inventory and payment, and the ordering context's own terminal facts. A sealed set so the {@link
 * OrderFulfilmentDefinition} handles every case exhaustively. Each input carries only business
 * fields (its correlation is the order id); transport metadata travels in the {@code
 * CommandContext}.
 */
public sealed interface OrderFulfilmentInput extends ProcessInput {

  String orderId();

  /** The order cleared for fulfilment: start a new flow awaiting stock reservation. */
  record ReadyForFulfilment(String orderId) implements OrderFulfilmentInput {}

  /** Inventory reserved stock; carries the reservation handle to release later. */
  record StockReserved(String orderId, String reservationId) implements OrderFulfilmentInput {}

  /** Inventory could not reserve stock; carries the failure evidence. */
  record StockReservationFailed(String orderId, String code, String reason)
      implements OrderFulfilmentInput {}

  /** Payment authorised the order's payment. */
  record PaymentAuthorized(String orderId) implements OrderFulfilmentInput {}

  /** Payment declined the order's payment; carries the decline code. */
  record PaymentDeclined(String orderId, String code, String reason)
      implements OrderFulfilmentInput {}

  /**
   * The payment deadline fired: the payment context answered neither way in time.
   *
   * <p>Not a fact from another context — it is the flow's own timer coming back as an ordinary
   * input, which is what makes a timeout just another row in the transition table rather than a
   * callback with its own rules. It is treated exactly like a decline, because from the order's
   * point of view "payment did not happen" is the same answer whichever way it arrived.
   */
  record PaymentTimedOut(String orderId) implements OrderFulfilmentInput {}

  /**
   * The stock deadline fired: inventory answered neither way in time.
   *
   * <p>The claim this input exists to retire is that payment was "the only step whose answer comes
   * from outside". Inventory answers over the same broker from the same kind of remote context, and
   * it answers <em>only</em> when it judges a business failure — a technical failure (an
   * optimistic-lock conflict, a validation error, a database outage) throws out of its handler and
   * publishes nothing at all. From this flow's point of view that silence is identical to the
   * payment context's.
   *
   * <p>Treated exactly like {@code StockReservationFailed}: no stock was reserved, so there is
   * nothing to release and the order can be cancelled directly. Only the recorded code differs.
   */
  record StockReservationTimedOut(String orderId) implements OrderFulfilmentInput {}

  /** Inventory released the previously reserved stock. */
  record StockReleased(String orderId, String reservationId) implements OrderFulfilmentInput {}

  /**
   * The stock-release deadline fired while compensating: the release was requested and never
   * confirmed.
   *
   * <p>Unlike every other timeout here, this one cannot end the wait, and that is a design
   * constraint rather than an omission. Cancelling from this step requires {@link
   * com.example.ordering.domain.order.CancellationReason.PaymentDeclinedAfterStockReleased}, whose
   * constructor demands a {@code StockReleaseRef} — evidence that the stock actually came back. A
   * timeout is precisely the absence of that evidence, so giving up here would mean fabricating it,
   * and the type system exists to make that impossible. The stock is genuinely still held; saying
   * otherwise would turn a stuck flow into a wrong one.
   *
   * <p>So the response is to ask again rather than to stop asking. See {@code
   * OrderFulfilmentDefinition#onAwaitingStockRelease}.
   */
  record StockReleaseTimedOut(String orderId) implements OrderFulfilmentInput {}

  /** The order confirmed — the successful terminal fact. */
  record OrderConfirmed(String orderId) implements OrderFulfilmentInput {}

  /** The order cancelled — the compensated terminal fact. */
  record OrderCancelled(String orderId) implements OrderFulfilmentInput {}
}
