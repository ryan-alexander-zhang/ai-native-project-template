package com.example.ordering.application.order;

import com.aipersimmon.ddd.cqrs.Command;
import com.example.ordering.domain.order.CancellationReason;

/**
 * Command to cancel an order (the fulfilment saga's compensation). It carries the evidence-bearing
 * {@link CancellationReason} the aggregate needs to authorise the cancellation — a bare order id
 * would let a caller assert "cancel" without saying why or proving that any compensation ran. No
 * result.
 */
public record CancelOrder(String orderId, CancellationReason reason) implements Command<Void> {}
