package com.aipersimmon.ddd.archunit.fixture.bad.ordering.application;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;

/**
 * Ill-behaved command handler: it reuses orchestration by depending on <em>another</em>
 * command handler ({@link BadConfirmOrderHandler}) instead of a domain service or a
 * non-handler application collaborator — the violation
 * {@code commandHandlersShouldNotDependOnOtherCommandHandlers} catches.
 */
public class BadCancelOrderHandler implements CommandHandler<BadCancelOrder, Void> {

    private final BadConfirmOrderHandler confirmOrderHandler;

    public BadCancelOrderHandler(BadConfirmOrderHandler confirmOrderHandler) {
        this.confirmOrderHandler = confirmOrderHandler;
    }

    @Override
    public Void handle(BadCancelOrder command, CommandContext context) {
        return confirmOrderHandler.handle(new BadConfirmOrder(command.orderId()), context);
    }
}
