package com.aipersimmon.ddd.archunit.fixture.bad.ordering.application;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;

/**
 * Ill-behaved command handler: it calls the infrastructure-only staged-dispatch entry
 * {@link CommandBus#sendAs(com.aipersimmon.ddd.cqrs.Command, CommandContext)}, which
 * would fabricate a message identity outside the sanctioned minting authorities — the
 * violation {@code commandHandlersAndApplicationShouldNotCallSendAs} catches. Business
 * dispatch uses {@code send(..)} / {@code send(.., cause)}.
 */
public class BadStagedDispatchHandler implements CommandHandler<BadStagedDispatch, Void> {

    private final CommandBus commandBus;

    public BadStagedDispatchHandler(CommandBus commandBus) {
        this.commandBus = commandBus;
    }

    @Override
    public Void handle(BadStagedDispatch command, CommandContext context) {
        return commandBus.sendAs(command, context);
    }
}
