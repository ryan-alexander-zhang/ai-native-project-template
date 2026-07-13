package com.aipersimmon.ddd.archunit.fixture.bad.ordering.application;

import com.aipersimmon.ddd.cqrs.Command;

/** Command handled by {@link BadCancelOrderHandler}. */
public record BadCancelOrder(String orderId) implements Command<Void> {
}
