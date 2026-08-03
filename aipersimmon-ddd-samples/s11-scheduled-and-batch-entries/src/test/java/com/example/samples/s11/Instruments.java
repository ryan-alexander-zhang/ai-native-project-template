package com.example.samples.s11;

import com.aipersimmon.ddd.application.DomainEventHandler;
import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandInterceptor;
import com.example.samples.s11.ordering.application.CloseExpiredOrder;
import com.example.samples.s11.ordering.domain.OrderClosed;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

/**
 * One test configuration for every test class here, so they share a Spring context where they can —
 * a per-class {@code @TestConfiguration} starts another container.
 *
 * <p>Four instruments, each answering a question the sweep raises: what time is it, which contexts did
 * the commands run under, which orders actually closed, and what happens when one command fails.
 */
@TestConfiguration(proxyBeanMethods = false)
class Instruments {

  /**
   * A clock the test moves. Deadlines are the subject here, so waiting for real time to pass would
   * make the suite slow and its timing assertions approximate — this makes "two minutes later" exact.
   */
  static final class TestClock extends Clock {
    private final AtomicReference<Instant> now =
        new AtomicReference<>(Instant.parse("2026-08-03T09:00:00Z"));

    @Override
    public Instant instant() {
      return now.get();
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    void advance(Duration by) {
      now.updateAndGet(instant -> instant.plus(by));
    }
  }

  /** Every dispatch the bus saw, with the context it ran under. */
  static final class Dispatches {
    record Seen(String commandType, String target, CommandContext context) {}

    private final List<Seen> seen = new CopyOnWriteArrayList<>();

    void record(Command<?> command, CommandContext context) {
      String target = command instanceof CloseExpiredOrder close ? close.orderId() : "";
      seen.add(new Seen(command.getClass().getSimpleName(), target, context));
    }

    List<CommandContext> contextsFor(String commandType) {
      return seen.stream()
          .filter(entry -> entry.commandType().equals(commandType))
          .map(Seen::context)
          .toList();
    }

    List<String> targetsFor(String commandType) {
      return seen.stream()
          .filter(entry -> entry.commandType().equals(commandType))
          .map(Seen::target)
          .toList();
    }

    void reset() {
      seen.clear();
    }
  }

  /** Orders whose close command should blow up, standing in for a genuine fault. */
  static final class Poison {
    private final Set<String> orderIds = ConcurrentHashMap.newKeySet();

    void poison(String orderId) {
      orderIds.add(orderId);
    }

    boolean contains(String orderId) {
      return orderIds.contains(orderId);
    }

    void reset() {
      orderIds.clear();
    }
  }

  /** Something to run once, in the middle of a dispatch — how the competing instance gets in. */
  static final class Interleave {
    private final AtomicReference<Runnable> once = new AtomicReference<>();

    void before(Runnable work) {
      once.set(work);
    }

    void fireOnce() {
      Runnable work = once.getAndSet(null);
      if (work != null) {
        work.run();
      }
    }

    void reset() {
      once.set(null);
    }
  }

  /** The events that actually escaped — the evidence a bulk statement cannot produce. */
  @DomainEventHandler
  static final class ClosedOrders {
    private final List<OrderClosed> events = new CopyOnWriteArrayList<>();

    @EventListener
    void on(OrderClosed event) {
      events.add(event);
    }

    List<String> closedIds() {
      return events.stream().map(event -> event.orderId().value()).toList();
    }

    void reset() {
      events.clear();
    }
  }

  /**
   * Sits innermost, so it runs after the transaction is open and immediately before the handler. It
   * records, optionally lets a competitor in, and optionally fails — all <em>before</em> proceeding,
   * so a poisoned command touches no row at all.
   */
  static final class DispatchProbe implements CommandInterceptor {
    private final Dispatches dispatches;
    private final Poison poison;
    private final Interleave interleave;

    DispatchProbe(Dispatches dispatches, Poison poison, Interleave interleave) {
      this.dispatches = dispatches;
      this.poison = poison;
      this.interleave = interleave;
    }

    @Override
    public <R> R intercept(Command<R> command, CommandContext context, Invocation<R> invocation) {
      dispatches.record(command, context);
      if (command instanceof CloseExpiredOrder close) {
        interleave.fireOnce();
        if (poison.contains(close.orderId())) {
          throw new IllegalStateException("simulated fault closing " + close.orderId());
        }
      }
      return invocation.proceed();
    }

    @Override
    public int order() {
      return 400;
    }
  }

  @Bean
  @Primary
  TestClock testClock() {
    return new TestClock();
  }

  @Bean
  Dispatches dispatches() {
    return new Dispatches();
  }

  @Bean
  Poison poison() {
    return new Poison();
  }

  @Bean
  Interleave interleave() {
    return new Interleave();
  }

  @Bean
  ClosedOrders closedOrders() {
    return new ClosedOrders();
  }

  @Bean
  @Order(400)
  CommandInterceptor dispatchProbe(Dispatches dispatches, Poison poison, Interleave interleave) {
    return new DispatchProbe(dispatches, poison, interleave);
  }
}
