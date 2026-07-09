package com.aipersimmon.ddd.cqrs.spring;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.aipersimmon.ddd.cqrs.CommandInterceptor;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.ResolvableType;

/**
 * A {@link CommandBus} that routes each command to the single {@link CommandHandler}
 * registered for its type and applies the {@link CommandInterceptor} chain around
 * the handler. Handlers are indexed by their command type, resolved from the
 * handler's generic signature; interceptors are ordered so the lowest
 * {@link CommandInterceptor#order()} wraps the others (outermost).
 */
public class RegistryCommandBus implements CommandBus {

    private final Map<Class<?>, CommandHandler<?, ?>> handlers = new HashMap<>();
    private final List<CommandInterceptor> interceptors;

    public RegistryCommandBus(List<CommandHandler<?, ?>> handlers,
                              List<CommandInterceptor> interceptors) {
        for (CommandHandler<?, ?> handler : handlers) {
            Class<?> commandType = commandTypeOf(handler);
            CommandHandler<?, ?> existing = this.handlers.put(commandType, handler);
            if (existing != null) {
                throw new IllegalStateException(
                        "Two command handlers registered for " + commandType.getName()
                                + ": " + existing.getClass().getName()
                                + " and " + handler.getClass().getName());
            }
        }
        this.interceptors = interceptors.stream()
                .sorted(Comparator.comparingInt(CommandInterceptor::order))
                .toList();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R> R send(Command<R> command) {
        CommandHandler<Command<R>, R> handler =
                (CommandHandler<Command<R>, R>) handlers.get(command.getClass());
        if (handler == null) {
            throw new IllegalStateException(
                    "No command handler registered for " + command.getClass().getName());
        }
        CommandInterceptor.Invocation<R> invocation = () -> handler.handle(command);
        for (int i = interceptors.size() - 1; i >= 0; i--) {
            CommandInterceptor interceptor = interceptors.get(i);
            CommandInterceptor.Invocation<R> next = invocation;
            invocation = () -> interceptor.intercept(command, next);
        }
        return invocation.proceed();
    }

    private static Class<?> commandTypeOf(CommandHandler<?, ?> handler) {
        Class<?> type = ResolvableType.forInstance(handler)
                .as(CommandHandler.class)
                .getGeneric(0)
                .resolve();
        if (type == null) {
            throw new IllegalStateException(
                    "Cannot resolve the command type of handler "
                            + handler.getClass().getName()
                            + "; declare it with a concrete Command type parameter");
        }
        return type;
    }
}
