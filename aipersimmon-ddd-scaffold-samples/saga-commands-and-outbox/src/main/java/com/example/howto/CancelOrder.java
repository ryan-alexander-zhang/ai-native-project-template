package com.example.howto;

import com.aipersimmon.ddd.cqrs.Command;

/** Command the saga sends to cancel an order when reservation fails. Returns nothing. */
public record CancelOrder(String orderId) implements Command<Void> {
}
