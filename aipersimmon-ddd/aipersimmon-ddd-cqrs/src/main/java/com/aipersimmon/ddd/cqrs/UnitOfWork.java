package com.aipersimmon.ddd.cqrs;

import java.util.function.Supplier;

/**
 * A transactional boundary for a piece of work. A command-bus transaction interceptor runs the
 * handler inside {@link #execute}, so the aggregate changes and the domain events drained
 * afterwards commit or roll back together. Keeping this as a port lets the handler and the bus stay
 * framework-free; an implementation backs it with the platform's transaction manager.
 */
public interface UnitOfWork {

  /**
   * Run {@code work} within one transaction and return its result, committing on normal return and
   * rolling back if it throws.
   */
  <R> R execute(Supplier<R> work);

  /** Run {@code work} within one transaction, discarding any result. */
  default void execute(Runnable work) {
    execute(
        () -> {
          work.run();
          return null;
        });
  }
}
