package com.example.samples.s19;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandInterceptor;
import com.example.samples.s19.ordering.application.CustomerStanding;
import com.example.samples.s19.ordering.application.WarehouseCalendar;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The instruments the tests read.
 *
 * <p>The two advisory sources are replaced by versions that record <em>whether a transaction was
 * active when they were consulted</em>. That is the honest way to observe where a precheck runs: no
 * probe in production code, no reasoning about interceptor orders — just the fact, recorded at the
 * moment of the call.
 */
@TestConfiguration(proxyBeanMethods = false)
class ObservingTheLayers {

  /** One record per observation: who looked, and whether a transaction was open at the time. */
  record Observation(String where, boolean insideTransaction) {}

  static final class Log {
    private final List<Observation> observations = new CopyOnWriteArrayList<>();

    void record(String where) {
      observations.add(
          new Observation(where, TransactionSynchronizationManager.isActualTransactionActive()));
    }

    List<Observation> observations() {
      return List.copyOf(observations);
    }

    List<String> order() {
      return observations.stream().map(Observation::where).toList();
    }

    boolean insideTransactionAt(String where) {
      return observations.stream()
          .filter(observation -> observation.where().equals(where))
          .map(Observation::insideTransaction)
          .findFirst()
          .orElseThrow(() -> new AssertionError(where + " was never reached"));
    }

    void reset() {
      observations.clear();
    }
  }

  static final class ObservedStanding implements CustomerStanding {
    private final Log log;

    ObservedStanding(Log log) {
      this.log = log;
    }

    @Override
    public boolean isBlocked(String customerId) {
      log.record("precheck:customer-standing");
      return customerId.startsWith("blocked");
    }
  }

  static final class ObservedCalendar implements WarehouseCalendar {
    private final Log log;
    private volatile boolean open = true;

    ObservedCalendar(Log log) {
      this.log = log;
    }

    @Override
    public boolean acceptingOrders() {
      log.record("precheck:warehouse-calendar");
      return open;
    }

    void close() {
      open = false;
    }

    void open() {
      open = true;
    }
  }

  /**
   * Sits at the innermost end of the chain, so it runs after the transaction interceptor has opened
   * one — which is exactly the contrast the tests need.
   */
  static final class HandlerSideProbe implements CommandInterceptor {
    private final Log log;

    HandlerSideProbe(Log log) {
      this.log = log;
    }

    @Override
    public <R> R intercept(Command<R> command, CommandContext context, Invocation<R> invocation) {
      log.record("handler");
      return invocation.proceed();
    }

    @Override
    public int order() {
      return 300;
    }
  }

  @Bean
  Log observationLog() {
    return new Log();
  }

  @Bean
  @Primary
  ObservedStanding observedStanding(Log log) {
    return new ObservedStanding(log);
  }

  @Bean
  @Primary
  ObservedCalendar observedCalendar(Log log) {
    return new ObservedCalendar(log);
  }

  @Bean
  HandlerSideProbe handlerSideProbe(Log log) {
    return new HandlerSideProbe(log);
  }
}
