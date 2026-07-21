package com.example.ordering.application.fulfilment;

import com.aipersimmon.ddd.cqrs.CommandContext;

/**
 * The order-fulfilment business port: the stable entry the ordering context drives the flow
 * through, named by the order id. It hides the durable runtime behind intention-revealing methods,
 * so the inbound adapter and the domain-event bridge depend on this — not on the process-manager
 * runtime or on how the instance is addressed. Swapping the provider (native / Temporal / Seata)
 * replaces the implementation without touching these call sites (design-00004 §13.1).
 */
public interface OrderFulfilmentProcess {

  /** An order was placed: start a new flow awaiting stock reservation. */
  void placed(String orderId);

  /** Inventory reserved stock. */
  void stockReserved(String orderId, String reservationId, CommandContext cause);

  /** Inventory could not reserve stock. */
  void stockReservationFailed(String orderId, String code, String reason, CommandContext cause);

  /** Payment authorised the charge. */
  void paymentAuthorized(String orderId, CommandContext cause);

  /** Payment declined the charge. */
  void paymentDeclined(String orderId, String code, String reason, CommandContext cause);

  /** Inventory released the previously reserved stock. */
  void stockReleased(String orderId, String reservationId, CommandContext cause);

  /** The order confirmed — the successful terminal fact. */
  void orderConfirmed(String orderId);

  /** The order cancelled — the compensated terminal fact. */
  void orderCancelled(String orderId);
}
