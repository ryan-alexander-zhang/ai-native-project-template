package com.example.ordering.application.order;

import com.aipersimmon.ddd.cqrs.Command;

/** Command to confirm an order (sent by the fulfilment saga on reservation). No result. */
public record ConfirmOrder(String orderId) implements Command<Void> {}
