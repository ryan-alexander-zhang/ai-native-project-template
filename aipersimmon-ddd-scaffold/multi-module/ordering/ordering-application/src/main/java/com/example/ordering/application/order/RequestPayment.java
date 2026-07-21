package com.example.ordering.application.order;

import com.aipersimmon.ddd.cqrs.Command;

/**
 * Ordering-internal command the saga sends to ask the payment context to charge for an order. Its
 * handler looks up the order's total and publishes the {@code PaymentRequested} integration event —
 * keeping the outbound-event concern in a use-case handler, so the process manager sends only
 * ordering commands and never touches the integration-event port itself. No result.
 *
 * <p>It carries the {@code paymentOperationId} — a <em>business idempotency key</em> the process
 * manager derives from the stable identity of the fact that triggered the charge (so an
 * at-least-once redelivery reuses it). The key rides the {@code PaymentRequested} event to the
 * payment context, which dedupes by it: a redelivered charge for the same operation must not charge
 * twice (design-00004 §13.2; complements the transport-level effect id, it does not replace it).
 */
public record RequestPayment(String orderId, String paymentOperationId) implements Command<Void> {}
