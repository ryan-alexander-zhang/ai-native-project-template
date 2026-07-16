package com.example.ordering.application.order;

import com.aipersimmon.ddd.cqrs.Command;

/**
 * Ordering-internal command the saga sends to ask the payment context to charge for an order. Its
 * handler looks up the order's total and publishes the {@code PaymentRequested} integration event —
 * keeping the outbound-event concern in a use-case handler, so the process manager sends only
 * ordering commands and never touches the integration-event port itself. No result.
 */
public record RequestPayment(String orderId) implements Command<Void> {
}
