package com.aipersimmon.ddd.cqrs.spring;

import com.aipersimmon.ddd.cqrs.Query;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.aipersimmon.ddd.cqrs.QueryHandler;
import com.aipersimmon.ddd.cqrs.QueryInterceptor;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.ResolvableType;

/**
 * A {@link QueryBus} that routes each query to the single {@link QueryHandler} registered for its
 * type, indexed by the query type resolved from the handler's generic signature. There is no
 * transaction here — a query neither changes state nor records events — but there is an optional
 * {@link QueryInterceptor} chain, so cross-cutting read concerns (logging, authorization,
 * slow-query observability) have the same kind of seam the command side has. With no interceptors
 * registered the bus behaves exactly as before.
 *
 * <p><strong>Handlers are resolved on first use, not while this bus is being built</strong> — the
 * same arrangement as {@link RegistryCommandBus}, for the same reason: a composite handler that
 * takes the bus in its constructor to ask sub-queries would otherwise be handed a half-built bus
 * and die with {@code BeanCurrentlyInCreationException}. The duplicate-handler check that used to
 * run as a side effect of building the index eagerly still fails the startup: {@link
 * #afterSingletonsInstantiated()} forces the build once the context is complete.
 */
public class RegistryQueryBus implements QueryBus, SmartInitializingSingleton {

  private final Supplier<List<QueryHandler<?, ?>>> handlerSource;
  private final Supplier<List<QueryInterceptor>> interceptorSource;

  private volatile Map<Class<?>, QueryHandler<?, ?>> registry;
  private volatile List<QueryInterceptor> chain;

  /** Eagerly-supplied handlers and no interceptors, for a test or a hand-built bus. */
  public RegistryQueryBus(List<QueryHandler<?, ?>> handlers) {
    this(() -> handlers, List::of);
  }

  /** Eagerly-supplied handlers and interceptors, for a test or a hand-built bus. */
  public RegistryQueryBus(List<QueryHandler<?, ?>> handlers, List<QueryInterceptor> interceptors) {
    this(() -> handlers, () -> interceptors);
  }

  /** Interceptor-less composing constructor, kept for existing callers. */
  public RegistryQueryBus(Supplier<List<QueryHandler<?, ?>>> handlers) {
    this(handlers, List::of);
  }

  /**
   * The composing constructor: both sources are read on first dispatch (or at the end of context
   * startup), never while this object is being constructed.
   */
  public RegistryQueryBus(
      Supplier<List<QueryHandler<?, ?>>> handlers, Supplier<List<QueryInterceptor>> interceptors) {
    this.handlerSource = handlers;
    this.interceptorSource = interceptors;
  }

  /**
   * Build the registry now that every singleton exists, so a duplicate handler or an unresolvable
   * query type fails the startup rather than the first dispatch that happens to need it.
   */
  @Override
  public void afterSingletonsInstantiated() {
    registry();
    chain();
  }

  private Map<Class<?>, QueryHandler<?, ?>> registry() {
    Map<Class<?>, QueryHandler<?, ?>> current = registry;
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

  private Map<Class<?>, QueryHandler<?, ?>> build() {
    Map<Class<?>, QueryHandler<?, ?>> byType = new HashMap<>();
    for (QueryHandler<?, ?> handler : handlerSource.get()) {
      Class<?> queryType = queryTypeOf(handler);
      QueryHandler<?, ?> existing = byType.put(queryType, handler);
      if (existing != null) {
        throw new IllegalStateException(
            "Two query handlers registered for "
                + queryType.getName()
                + ": "
                + existing.getClass().getName()
                + " and "
                + handler.getClass().getName());
      }
    }
    return byType;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <R> R ask(Query<R> query) {
    QueryHandler<Query<R>, R> handler =
        (QueryHandler<Query<R>, R>) registry().get(query.getClass());
    if (handler == null) {
      throw new IllegalStateException(
          "No query handler registered for " + query.getClass().getName());
    }
    QueryInterceptor.Invocation<R> invocation = () -> handler.handle(query);
    // Fold the sorted chain from the handler outwards, so the lowest order() ends up outermost —
    // the same shape the command bus gives its interceptors.
    List<QueryInterceptor> interceptors = chain();
    for (int i = interceptors.size() - 1; i >= 0; i--) {
      QueryInterceptor interceptor = interceptors.get(i);
      QueryInterceptor.Invocation<R> next = invocation;
      invocation = () -> interceptor.intercept(query, next);
    }
    return invocation.proceed();
  }

  private List<QueryInterceptor> chain() {
    List<QueryInterceptor> current = chain;
    if (current == null) {
      synchronized (this) {
        current = chain;
        if (current == null) {
          current =
              interceptorSource.get().stream()
                  .sorted(Comparator.comparingInt(QueryInterceptor::order))
                  .toList();
          chain = current;
        }
      }
    }
    return current;
  }

  private static Class<?> queryTypeOf(QueryHandler<?, ?> handler) {
    Class<?> type =
        ResolvableType.forInstance(handler).as(QueryHandler.class).getGeneric(0).resolve();
    if (type == null) {
      throw new IllegalStateException(
          "Cannot resolve the query type of handler "
              + handler.getClass().getName()
              + "; declare it with a concrete Query type parameter");
    }
    return type;
  }
}
