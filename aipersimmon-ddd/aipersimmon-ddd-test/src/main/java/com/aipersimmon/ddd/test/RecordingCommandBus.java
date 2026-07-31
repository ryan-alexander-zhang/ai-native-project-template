package com.aipersimmon.ddd.test;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.tenancy.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * A {@link CommandBus} that records every dispatch instead of handling it — the official test
 * double, so a consuming team does not re-invent one and get the identity semantics subtly wrong
 * (issue-00140). It keeps the real bus's rules:
 *
 * <ul>
 *   <li>{@link #send(Command)} mints a root {@link CommandContext} under the ambient {@link
 *       TenantContext#effective() tenant} — a fresh correlation, like {@code RegistryCommandBus};
 *   <li>{@link #send(Command, CommandContext)} derives a child of the cause, so causation
 *       assertions ({@code context.causationId()} names the parent) hold exactly as in production;
 *   <li>{@link #sendAs} records the context <em>verbatim</em>, minting nothing — so a test can
 *       assert that a redelivered effect reaches the bus under its one stable messageId.
 * </ul>
 *
 * <p>Handlers are not consulted; {@code send} returns {@code null} unless the command's type was
 * {@linkplain #returning stubbed}. Recorded dispatches are read back through {@link #dispatches()}
 * and the convenience views. Instances are safe to share across the test's threads.
 */
public final class RecordingCommandBus implements CommandBus {

  /** Which entry point a dispatch came through — the identity rule that applied. */
  public enum Kind {
    ROOT,
    CHILD,
    STAGED
  }

  /** One recorded dispatch: the command, the context it ran under, and the entry point. */
  public record Dispatch(Command<?> command, CommandContext context, Kind kind) {}

  private final List<Dispatch> dispatches = new CopyOnWriteArrayList<>();
  private final Map<Class<?>, Function<Command<?>, ?>> stubs = new ConcurrentHashMap<>();
  private final AtomicLong ids = new AtomicLong();

  /** Stub the result for every dispatch of {@code commandType}; unstubbed types return null. */
  @SuppressWarnings("unchecked")
  public <R, C extends Command<R>> RecordingCommandBus returning(
      Class<C> commandType, Function<C, R> result) {
    stubs.put(commandType, command -> result.apply((C) command));
    return this;
  }

  @Override
  public <R> R send(Command<R> command) {
    return record(command, CommandContext.root(TenantContext.effective(), nextId()), Kind.ROOT);
  }

  @Override
  public <R> R send(Command<R> command, CommandContext cause) {
    return record(command, cause.deriveChild(nextId()), Kind.CHILD);
  }

  @Override
  public <R> R sendAs(Command<R> command, CommandContext messageContext) {
    return record(command, messageContext, Kind.STAGED);
  }

  @SuppressWarnings("unchecked")
  private <R> R record(Command<R> command, CommandContext context, Kind kind) {
    dispatches.add(new Dispatch(command, context, kind));
    Function<Command<?>, ?> stub = stubs.get(command.getClass());
    return stub == null ? null : (R) stub.apply(command);
  }

  /** Every dispatch in order, with its context and entry point. */
  public List<Dispatch> dispatches() {
    return List.copyOf(dispatches);
  }

  /** The dispatched commands, in order. */
  public List<Command<?>> commands() {
    return dispatches.stream().map(Dispatch::command).toList();
  }

  /** The contexts the commands were dispatched under, in order. */
  public List<CommandContext> contexts() {
    return dispatches.stream().map(Dispatch::context).toList();
  }

  /** The dispatched commands of one type, in order. */
  @SuppressWarnings("unchecked")
  public <C extends Command<?>> List<C> commandsOf(Class<C> commandType) {
    return dispatches.stream()
        .map(Dispatch::command)
        .filter(commandType::isInstance)
        .map(command -> (C) command)
        .toList();
  }

  /** Forget everything recorded so far (stubs stay). */
  public void reset() {
    dispatches.clear();
  }

  private String nextId() {
    return "cmd-" + ids.incrementAndGet();
  }
}
