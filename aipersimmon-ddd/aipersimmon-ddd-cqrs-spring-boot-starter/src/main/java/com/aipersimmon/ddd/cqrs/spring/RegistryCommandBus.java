package com.aipersimmon.ddd.cqrs.spring;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandContexts;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.aipersimmon.ddd.cqrs.CommandInterceptor;
import com.aipersimmon.ddd.tenancy.TenantContext;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.SmartInitializingSingleton;
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
 * <p><strong>Handlers are resolved on first use, not while this bus is being built.</strong>
 * Resolving them in the constructor meant the bus's own creation instantiated every handler — so a
 * handler that takes the bus in its constructor asked Spring for a bean that was half-built and got
 * {@code BeanCurrentlyInCreationException}. That composition is the one the architecture rules
 * steer people towards: a handler may not depend on another handler, and the bus is the alternative
 * they name. The checks that used to happen as a side effect of building the index eagerly have not
 * been given up — {@link #afterSingletonsInstantiated()} forces the same build once the context is
 * complete, so a duplicate handler is still a startup failure rather than a surprise on the first
 * dispatch of that command.
 *
 * <p>{@link #sendAs(Command, CommandContext)} is the exception: it dispatches under a context
 * minted upstream by a durable store (effect relay / outbox), verbatim, so a redelivered effect
 * keeps its messageId. It mints no id.
 */
public class RegistryCommandBus implements CommandBus, SmartInitializingSingleton {

  private final Supplier<List<CommandHandler<?, ?>>> handlerSource;
  private final Supplier<List<CommandInterceptor>> interceptorSource;
  private final Supplier<String> idGenerator;

  private volatile Registry registry;

  /** The resolved routing table and interceptor chain, built once. */
  private record Registry(
      Map<Class<?>, CommandHandler<?, ?>> handlers, List<CommandInterceptor> interceptors) {}

  /** Eagerly-supplied collaborators, for a test or a hand-built bus. */
  public RegistryCommandBus(
      List<CommandHandler<?, ?>> handlers,
      List<CommandInterceptor> interceptors,
      Supplier<String> idGenerator) {
    this(() -> handlers, () -> interceptors, idGenerator);
  }

  /**
   * The composing constructor: the two sources are read on first dispatch (or at the end of context
   * startup), never while this object is being constructed.
   *
   * @param idGenerator supplies each command's message id. Required: there is no defaulting
   *     overload, so a caller cannot accidentally fall back to a non-time-ordered id (see {@code
   *     issue-00053}).
   */
  public RegistryCommandBus(
      Supplier<List<CommandHandler<?, ?>>> handlers,
      Supplier<List<CommandInterceptor>> interceptors,
      Supplier<String> idGenerator) {
    this.handlerSource = handlers;
    this.interceptorSource = interceptors;
    this.idGenerator = idGenerator;
  }

  /**
   * Build the registry now that every singleton exists, so a duplicate handler or an unresolvable
   * command type fails the startup rather than the first dispatch that happens to need it.
   *
   * <p>This runs after the context is populated, which is the whole point: doing the same work in
   * the constructor is what made the bus uninjectable into a handler.
   */
  @Override
  public void afterSingletonsInstantiated() {
    registry();
  }

  private Registry registry() {
    Registry current = registry;
    if (current == null) {
      synchronized (this) {
        current = registry;
        if (current == null) {
          current = build();
          registry = current;
        }
      }
    }
    return current;
  }

  private Registry build() {
    Map<Class<?>, CommandHandler<?, ?>> byType = new HashMap<>();
    for (CommandHandler<?, ?> handler : handlerSource.get()) {
      Class<?> commandType = commandTypeOf(handler);
      CommandHandler<?, ?> existing = byType.put(commandType, handler);
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
    return new Registry(
        byType,
        interceptorSource.get().stream()
            .sorted(Comparator.comparingInt(CommandInterceptor::order))
            .toList());
  }

  @Override
  public <R> R send(Command<R> command) {
    return dispatch(command, CommandContext.root(TenantContext.effective(), idGenerator.get()));
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
    Registry resolved = registry();
    CommandHandler<Command<R>, R> handler =
        (CommandHandler<Command<R>, R>) resolved.handlers().get(command.getClass());
    if (handler == null) {
      throw new IllegalStateException(
          "No command handler registered for " + command.getClass().getName());
    }
    List<CommandInterceptor> interceptors = resolved.interceptors();
    CommandInterceptor.Invocation<R> invocation = () -> handler.handle(command, context);
    for (int i = interceptors.size() - 1; i >= 0; i--) {
      CommandInterceptor interceptor = interceptors.get(i);
      CommandInterceptor.Invocation<R> next = invocation;
      invocation = () -> interceptor.intercept(command, context, next);
    }
    // Bound around the whole chain, not just the handler: the repository publishes domain events
    // inside the transaction interceptor, and a synchronous subscriber there is exactly the reader
    // CommandContexts exists for (issue-00137). The scope restores, so a nested send inside a
    // handler hands the outer dispatch its context back.
    CommandInterceptor.Invocation<R> outermost = invocation;
    return CommandContexts.runAs(context, outermost::proceed);
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
