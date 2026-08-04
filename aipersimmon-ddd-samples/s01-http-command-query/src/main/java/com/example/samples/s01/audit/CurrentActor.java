package com.example.samples.s01.audit;

import com.aipersimmon.ddd.operationlog.model.Actor;
import java.util.Optional;

/**
 * The thread-bound actor, established at a trusted boundary and read by the resolver.
 *
 * <p>A {@code ThreadLocal} rather than a request-scoped bean, for the same reason the library's own
 * {@code TenantContext} is one: the resolver is called from an interceptor around the command bus,
 * which may be reached from a scheduler or a message consumer where no request scope exists, and a
 * holder that throws outside a request would make the audit log a reason commands cannot run.
 *
 * <p><strong>Which makes clearing it the whole safety property.</strong> A thread that served a
 * request and was returned to the pool still holds the binding; the next thing to run on that thread
 * — a scheduled sweep, a retry, anything not going through the filter — would record its operations
 * as having been performed by whoever happened to have used that thread last. That is not a
 * hypothetical: {@code ActorResolutionTest} constructs it, and the only thing standing between it and
 * production is the {@code finally} in {@link ActorBindingFilter}.
 */
public final class CurrentActor {

  private static final ThreadLocal<Actor> BOUND = new ThreadLocal<>();

  private CurrentActor() {}

  /** The actor bound to this thread, if any. */
  public static Optional<Actor> current() {
    return Optional.ofNullable(BOUND.get());
  }

  /** Bind the actor for this thread. Trusted boundaries only. */
  public static void bind(Actor actor) {
    if (actor == null) {
      throw new IllegalArgumentException("actor must not be null");
    }
    BOUND.set(actor);
  }

  /** Unbind. Always from a {@code finally}. */
  public static void clear() {
    BOUND.remove();
  }
}
