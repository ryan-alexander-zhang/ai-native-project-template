package com.acme.samples.s2.ordering.infrastructure.command;

import com.acme.samples.s2.shared.Command;
import com.acme.samples.s2.shared.CommandBus;
import com.acme.samples.s2.shared.CommandHandler;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.ResolvableType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Core bus: routes a command to the single {@link CommandHandler} registered for
 * its type. Handlers are discovered as beans; each one's command type is resolved
 * from its generic signature. This is the innermost layer — decorators
 * (Logging / Validation / Transaction) wrap it.
 */
public class SimpleCommandBus implements CommandBus {

    private final Map<Class<?>, CommandHandler<?, ?>> handlers = new HashMap<>();

    public SimpleCommandBus(List<CommandHandler<?, ?>> handlerBeans) {
        for (CommandHandler<?, ?> handler : handlerBeans) {
            handlers.put(commandTypeOf(handler), handler);
        }
    }

    private Class<?> commandTypeOf(CommandHandler<?, ?> handler) {
        Class<?> target = AopUtils.getTargetClass(handler);   // unwrap Spring proxies
        Class<?> commandType = ResolvableType.forClass(target)
                .as(CommandHandler.class).getGeneric(0).resolve();
        if (commandType == null) {
            throw new IllegalStateException("cannot resolve command type for " + target.getName());
        }
        return commandType;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R> R dispatch(Command<R> command) {
        CommandHandler<Command<R>, R> handler =
                (CommandHandler<Command<R>, R>) handlers.get(command.getClass());
        if (handler == null) {
            throw new IllegalStateException("no handler registered for " + command.getClass().getName());
        }
        return handler.handle(command);
    }
}
