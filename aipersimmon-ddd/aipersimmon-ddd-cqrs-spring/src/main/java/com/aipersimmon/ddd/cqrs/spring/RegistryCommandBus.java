package com.aipersimmon.ddd.cqrs.spring;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.aipersimmon.ddd.cqrs.CommandInterceptor;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.MDC;
import org.springframework.core.ResolvableType;

/**
 * A {@link CommandBus} that routes each command to the single {@link CommandHandler}
 * registered for its type and applies the {@link CommandInterceptor} chain around
 * the handler. Handlers are indexed by their command type, resolved from the
 * handler's generic signature; interceptors are ordered so the lowest
 * {@link CommandInterceptor#order()} wraps the others (outermost).
 *
 * <p>The bus mints each command's {@link CommandContext} id. A root
 * {@link #send(Command)} seeds a fresh correlation and picks up the ambient trace id
 * from the {@code traceId} MDC key (set by the web trace-id filter); a
 * {@link #send(Command, CommandContext)} derives a child of the triggering message,
 * so correlation and causation propagate down the chain.
 */
public class RegistryCommandBus implements CommandBus {

    /** MDC key populated by the web trace-id filter; read to seed a root command's trace. */
    static final String TRACE_ID_MDC_KEY = "traceId";

    private final Map<Class<?>, CommandHandler<?, ?>> handlers = new HashMap<>();
    private final List<CommandInterceptor> interceptors;
    private final Supplier<String> idGenerator;

    public RegistryCommandBus(List<CommandHandler<?, ?>> handlers,
                              List<CommandInterceptor> interceptors) {
        this(handlers, interceptors, () -> UUID.randomUUID().toString());
    }

    /**
     * @param idGenerator supplies each command's message id (default: random UUID);
     *                    injectable so tests can make ids deterministic
     */
    public RegistryCommandBus(List<CommandHandler<?, ?>> handlers,
                              List<CommandInterceptor> interceptors,
                              Supplier<String> idGenerator) {
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
        this.idGenerator = idGenerator;
    }

    @Override
    public <R> R send(Command<R> command) {
        return dispatch(command, CommandContext.root(idGenerator.get(), MDC.get(TRACE_ID_MDC_KEY)));
    }

    @Override
    public <R> R send(Command<R> command, CommandContext cause) {
        return dispatch(command, cause.deriveChild(idGenerator.get()));
    }

    @SuppressWarnings("unchecked")
    private <R> R dispatch(Command<R> command, CommandContext context) {
        CommandHandler<Command<R>, R> handler =
                (CommandHandler<Command<R>, R>) handlers.get(command.getClass());
        if (handler == null) {
            throw new IllegalStateException(
                    "No command handler registered for " + command.getClass().getName());
        }
        CommandInterceptor.Invocation<R> invocation = () -> handler.handle(command, context);
        for (int i = interceptors.size() - 1; i >= 0; i--) {
            CommandInterceptor interceptor = interceptors.get(i);
            CommandInterceptor.Invocation<R> next = invocation;
            invocation = () -> interceptor.intercept(command, context, next);
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
