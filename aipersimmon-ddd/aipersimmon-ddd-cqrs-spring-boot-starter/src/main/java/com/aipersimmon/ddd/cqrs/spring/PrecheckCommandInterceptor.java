package com.aipersimmon.ddd.cqrs.spring;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandInterceptor;
import com.aipersimmon.ddd.cqrs.CommandPrecheck;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.ResolvableType;

/**
 * Runs every {@link CommandPrecheck} registered for a command's type, ordered between validation
 * ({@link ValidationCommandInterceptor#ORDER}) and the transaction ({@link
 * TransactionCommandInterceptor#ORDER}): the command has already passed Bean Validation, and no
 * transaction — no database connection — has been opened yet. That slot is the point (issue-00141):
 * a precheck is allowed to be a slow cross-context read precisely because nothing scarce is being
 * held while it runs.
 *
 * <p>Prechecks are matched by their generic type parameter, the same way the bus matches handlers —
 * and resolved lazily for the same reason ({@code BeanCurrentlyInCreationException}, see {@link
 * RegistryCommandBus}): a precheck may take the bus in its constructor. {@link
 * #afterSingletonsInstantiated()} forces the same build once the context is complete, so an
 * unresolvable precheck type parameter still fails startup rather than the first dispatch.
 */
public class PrecheckCommandInterceptor implements CommandInterceptor, SmartInitializingSingleton {

  /** Between validation (100) and the transaction (200). */
  public static final int ORDER = 150;

  private final Supplier<List<CommandPrecheck<?>>> precheckSource;

  private volatile Map<Class<?>, List<CommandPrecheck<?>>> registry;

  /** Eagerly-supplied prechecks, for a test or a hand-built chain. */
  public PrecheckCommandInterceptor(List<CommandPrecheck<?>> prechecks) {
    this(() -> prechecks);
  }

  /** The composing constructor: the source is read on first dispatch, never during construction. */
  public PrecheckCommandInterceptor(Supplier<List<CommandPrecheck<?>>> prechecks) {
    this.precheckSource = prechecks;
  }

  @Override
  public void afterSingletonsInstantiated() {
    registry();
  }

  @Override
  @SuppressWarnings("unchecked")
  public <R> R intercept(Command<R> command, CommandContext context, Invocation<R> invocation) {
    List<CommandPrecheck<?>> prechecks = registry().get(command.getClass());
    if (prechecks != null) {
      for (CommandPrecheck<?> precheck : prechecks) {
        ((CommandPrecheck<Command<R>>) precheck).check(command, context);
      }
    }
    return invocation.proceed();
  }

  @Override
  public int order() {
    return ORDER;
  }

  private Map<Class<?>, List<CommandPrecheck<?>>> registry() {
    Map<Class<?>, List<CommandPrecheck<?>>> current = registry;
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

  private Map<Class<?>, List<CommandPrecheck<?>>> build() {
    Map<Class<?>, List<CommandPrecheck<?>>> byType = new HashMap<>();
    for (CommandPrecheck<?> precheck : precheckSource.get()) {
      byType.computeIfAbsent(commandTypeOf(precheck), key -> new ArrayList<>()).add(precheck);
    }
    return byType;
  }

  private static Class<?> commandTypeOf(CommandPrecheck<?> precheck) {
    Class<?> type =
        ResolvableType.forInstance(precheck).as(CommandPrecheck.class).getGeneric(0).resolve();
    // Stricter than null: an erased type parameter resolves to its bound (the Command interface
    // itself), and a precheck registered under the interface never matches a dispatch — it would
    // not fail, it would silently never run. Prechecks are matched by the concrete command class,
    // so only a concrete one is a valid registration.
    if (type == null || type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
      throw new IllegalStateException(
          "Cannot resolve the command type of precheck "
              + precheck.getClass().getName()
              + " (got "
              + (type == null ? "nothing" : type.getName())
              + "); declare it with a concrete Command type parameter — prechecks are matched by"
              + " the command's exact class, so one registered against an interface or abstract"
              + " type would silently never run");
    }
    return type;
  }
}
