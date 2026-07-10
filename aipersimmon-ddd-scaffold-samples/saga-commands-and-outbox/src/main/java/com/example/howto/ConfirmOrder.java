package com.example.howto;

import com.aipersimmon.ddd.cqrs.Command;

/** Command the saga sends to confirm an order once stock is reserved. Returns nothing. */
public record ConfirmOrder(String orderId) implements Command<Void> {
}
