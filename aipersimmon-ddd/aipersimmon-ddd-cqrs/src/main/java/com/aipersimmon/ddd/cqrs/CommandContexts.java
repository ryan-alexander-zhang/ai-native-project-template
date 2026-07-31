package com.aipersimmon.ddd.cqrs;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * The ambient holder of the {@link CommandContext} currently being dispatched, bound by the {@link
 * CommandBus} for exactly the duration of each dispatch.
 *
 * <p>{@code CommandContext} normally travels as an explicit parameter — {@code send(command,
 * cause)} — and explicit passing remains the rule wherever a parameter can reach. This holder
 * covers the one propagation path a parameter cannot reach: a synchronous domain-event subscriber.
 * The repository's {@code save} publishes the aggregate's events on the handler's call stack,
 * within the command's transaction, but the subscriber's method signature is fixed by the event —
 * there is no place to hand it the context. Before this scope existed such a subscriber could only
 * mint a fresh root context, so the correlation chain the bus works to maintain broke at every
 * domain-event hop.
 *
 * <p>Read {@link #current()} only from code that is (transitively) synchronous under a dispatch,
 * and treat an empty result as "not under a dispatch" — a subscriber with a legitimate standalone
 * entry point falls back to minting a root context. The binding is thread-scoped and does not cross
 * a thread hop, executor, or {@code @Async} boundary; asynchronous work must be handed the context
 * as data.
 *
 * <p>{@link #runAs(CommandContext, Supplier)} is the bus's entry — business code never binds. There
 * is deliberately no {@code set}/{@code clear} pair: a scope that cannot forget to restore is the
 * only kind that nested dispatch (a handler sending a follow-up command) survives. Contrast {@code
 * TenantContext}, which carries an immutable request identity and so needs trusted-boundary {@code
 * set}; this holder carries per-dispatch state whose writer is always on the stack above.
 */
public final class CommandContexts {

  private static final ThreadLocal<CommandContext> CURRENT = new ThreadLocal<>();

  private CommandContexts() {}

  /** The context of the dispatch this thread is currently inside, if any. */
  public static Optional<CommandContext> current() {
    return Optional.ofNullable(CURRENT.get());
  }

  /**
   * Runs {@code work} with {@code context} bound as the current dispatch, restoring the previous
   * binding afterwards. Bus implementations only.
   */
  public static <T> T runAs(CommandContext context, Supplier<T> work) {
    if (context == null) {
      throw new IllegalArgumentException("context must not be null");
    }
    CommandContext previous = CURRENT.get();
    CURRENT.set(context);
    try {
      return work.get();
    } finally {
      if (previous == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(previous);
      }
    }
  }
}
