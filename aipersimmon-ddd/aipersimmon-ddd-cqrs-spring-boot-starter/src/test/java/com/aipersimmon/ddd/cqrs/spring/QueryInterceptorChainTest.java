package com.aipersimmon.ddd.cqrs.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.aipersimmon.ddd.cqrs.Query;
import com.aipersimmon.ddd.cqrs.QueryHandler;
import com.aipersimmon.ddd.cqrs.QueryInterceptor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * The read side's interceptor seam (issue-00150): cross-cutting read concerns — logging,
 * authorization, slow-query observability — get the same kind of hook the command side has, without
 * the framework registering anything by itself.
 */
class QueryInterceptorChainTest {

  private record CountThings(String what) implements Query<Integer> {}

  private static final class CountHandler implements QueryHandler<CountThings, Integer> {
    @Override
    public Integer handle(CountThings query) {
      return 42;
    }
  }

  /** Records its passage on both sides of proceed(), under a name, at an order. */
  private static QueryInterceptor tracing(String name, int order, List<String> trace) {
    return new QueryInterceptor() {
      @Override
      public <R> R intercept(Query<R> query, Invocation<R> invocation) {
        trace.add(name + ">");
        R result = invocation.proceed();
        trace.add("<" + name);
        return result;
      }

      @Override
      public int order() {
        return order;
      }
    };
  }

  @Test
  void interceptorsWrapTheHandlerLowestOrderOutermost() {
    List<String> trace = new ArrayList<>();
    RegistryQueryBus bus =
        new RegistryQueryBus(
            List.of(new CountHandler()),
            // Registered inner-first on purpose: the chain must sort by order(), not trust the
            // registration order Spring happens to produce.
            List.of(tracing("inner", 100, trace), tracing("outer", 0, trace)));

    assertEquals(42, bus.ask(new CountThings("boxes")));
    assertEquals(List.of("outer>", "inner>", "<inner", "<outer"), trace);
  }

  /** An interceptor may answer without proceeding — a cache hit, an authorization refusal. */
  @Test
  void anInterceptorCanShortCircuitTheHandler() {
    AtomicBoolean handlerRan = new AtomicBoolean();
    QueryHandler<CountThings, Integer> handler =
        new QueryHandler<>() {
          @Override
          public Integer handle(CountThings query) {
            handlerRan.set(true);
            return 42;
          }
        };
    QueryInterceptor cached =
        new QueryInterceptor() {
          @Override
          @SuppressWarnings("unchecked")
          public <R> R intercept(Query<R> query, Invocation<R> invocation) {
            return (R) Integer.valueOf(7);
          }
        };
    RegistryQueryBus bus = new RegistryQueryBus(List.of(handler), List.of(cached));

    assertEquals(7, bus.ask(new CountThings("boxes")));
    assertFalse(handlerRan.get(), "the short-circuit must not reach the handler");
  }

  @Test
  void aBusWithNoInterceptorsBehavesExactlyAsBefore() {
    RegistryQueryBus bus = new RegistryQueryBus(List.of(new CountHandler()));

    assertEquals(42, bus.ask(new CountThings("boxes")));
  }
}
