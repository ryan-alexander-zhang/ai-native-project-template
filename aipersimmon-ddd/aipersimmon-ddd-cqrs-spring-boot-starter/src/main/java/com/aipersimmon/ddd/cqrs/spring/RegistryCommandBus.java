package com.aipersimmon.ddd.cqrs.spring;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.aipersimmon.ddd.cqrs.CommandInterceptor;
import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.TenantId;
import com.aipersimmon.ddd.tenancy.Tenants;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.core.ResolvableType;

/**
 * A {@link CommandBus} that routes each command to the single {@link CommandHandler} registered for
 * its type and applies the {@link CommandInterceptor} chain around the handler. Handlers are
 * indexed by their command type, resolved from the handler's generic signature; interceptors are
 * ordered so the lowest {@link CommandInterceptor#order()} wraps the others (outermost).
 *
 * <p>The bus mints each command's {@link CommandContext} id. A root {@link #send(Command)} seeds a
 * fresh correlation; a {@link #send(Command, CommandContext)} derives a child of the triggering
 * message, so correlation and causation propagate down the chain. Distributed-trace identity is
 * handled out of band by the OpenTelemetry context, not seeded here.
 *
 * <p>{@link #sendAs(Command, CommandContext)} is the exception: it dispatches under a context
 * minted upstream by a durable store (effect relay / outbox), verbatim, so a redelivered effect
 * keeps its messageId. It mints no id.
 */
public class RegistryCommandBus implements CommandBus {

  private final Map<Class<?>, CommandHandler<?, ?>> handlers = new HashMap<>();
  private final List<CommandInterceptor> interceptors;
  private final Supplier<String> idGenerator;

  /**
   * @param idGenerator supplies each command's message id. Required: there is no defaulting
   *     overload, so a caller cannot accidentally fall back to a non-time-ordered id (see {@code
   *     issue-00053}). Auto-configuration passes the {@link
   *     com.aipersimmon.ddd.core.id.IdGenerator} bean; tests pass a deterministic supplier.
   */
  public RegistryCommandBus(
      List<CommandHandler<?, ?>> handlers,
      List<CommandInterceptor> interceptors,
      Supplier<String> idGenerator) {
    for (CommandHandler<?, ?> handler : handlers) {
      Class<?> commandType = commandTypeOf(handler);
      CommandHandler<?, ?> existing = this.handlers.put(commandType, handler);
      if (existing != null) {
        throw new IllegalStateException(
            "Two command handlers registered for "
                + commandType.getName()
                + ": "
                + existing.getClass().getName()
                + " and "
                + handler.getClass().getName());
      }
    }
    this.interceptors =
        interceptors.stream().sorted(Comparator.comparingInt(CommandInterceptor::order)).toList();
    this.idGenerator = idGenerator;
  }

  @Override
  public <R> R send(Command<R> command) {
    String tenant = TenantContext.current().map(TenantId::value).orElse(Tenants.ROOT.value());
    return dispatch(command, CommandContext.root(tenant, idGenerator.get()));
  }

  @Override
  public <R> R send(Command<R> command, CommandContext cause) {
    return dispatch(command, cause.deriveChild(idGenerator.get()));
  }

  /**
   * Dispatches a command under an identity minted upstream (a Process Manager effect relay, an
   * outbox), using {@code messageContext} verbatim — no {@code idGenerator} call, no {@link
   * CommandContext#deriveChild}. Redelivering the same persisted effect therefore reaches the
   * handler under the same messageId, so the handler can dedupe.
   */
  @Override
  public <R> R sendAs(Command<R> command, CommandContext messageContext) {
    return dispatch(command, messageContext);
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
    Class<?> type =
        ResolvableType.forInstance(handler).as(CommandHandler.class).getGeneric(0).resolve();
    if (type == null) {
      throw new IllegalStateException(
          "Cannot resolve the command type of handler "
              + handler.getClass().getName()
              + "; declare it with a concrete Command type parameter");
    }
    return type;
  }
}
