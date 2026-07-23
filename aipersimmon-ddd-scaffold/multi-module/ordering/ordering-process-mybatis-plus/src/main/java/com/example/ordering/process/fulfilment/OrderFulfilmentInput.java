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

  /** Inventory released the previously reserved stock. */
  record StockReleased(String orderId, String reservationId) implements OrderFulfilmentInput {}

  /** The order confirmed — the successful terminal fact. */
  record OrderConfirmed(String orderId) implements OrderFulfilmentInput {}

  /** The order cancelled — the compensated terminal fact. */
  record OrderCancelled(String orderId) implements OrderFulfilmentInput {}
}
