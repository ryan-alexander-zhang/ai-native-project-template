package com.example.ordering.application.order;

import com.aipersimmon.ddd.cqrs.Command;

/** Command to cancel an order (the fulfilment saga's compensation). No result. */
public record CancelOrder(String orderId) implements Command<Void> {
}
