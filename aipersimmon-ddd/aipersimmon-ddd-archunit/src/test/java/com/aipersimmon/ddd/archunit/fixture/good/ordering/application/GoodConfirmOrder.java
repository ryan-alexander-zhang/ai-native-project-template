package com.aipersimmon.ddd.archunit.fixture.good.ordering.application;

import com.aipersimmon.ddd.cqrs.Command;

/** A command the good handler accepts; returns nothing. */
public record GoodConfirmOrder(String orderId) implements Command<Void> {
}
