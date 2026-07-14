package com.aipersimmon.ddd.archunit.fixture.bad.ordering.application;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;

/** A perfectly ordinary command handler — the one {@link BadCancelOrderHandler} wrongly reuses. */
public class BadConfirmOrderHandler implements CommandHandler<BadConfirmOrder, Void> {

    @Override
    public Void handle(BadConfirmOrder command, CommandContext context) {
        return null;
    }
}
