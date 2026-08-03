package com.example.samples.s06;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandInterceptor;
import com.aipersimmon.ddd.cqrs.CommandPrecheck;
import com.example.samples.s06.ordering.application.PlaceOrder;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Records whether a transaction was active at two points: during a precheck, and inside the handler.
 *
 * <p>That pair is the sample's central mechanical claim — the remote call happens before any transaction
 * exists — and it is invisible from outside the process. A second precheck is legitimate: the framework
 * runs every precheck registered for a command type, in bean order.
 */
@TestConfiguration(proxyBeanMethods = false)
class TransactionProbes {

  static final class Recorder {
    final AtomicReference<Boolean> duringPrecheck = new AtomicReference<>();
    final AtomicReference<Boolean> insideHandler = new AtomicReference<>();

    void clear() {
      duringPrecheck.set(null);
      insideHandler.set(null);
    }
  }

  @Bean
  Recorder transactionStateRecorder() {
    return new Recorder();
  }

  /**
   * An anonymous class, and <strong>not a lambda</strong>.
   *
   * <p>Prechecks are indexed by their type parameter, and a lambda erases it: the registry refuses one at
   * startup with "Cannot resolve the command type of precheck ... declare it with a concrete Command type
   * parameter" ({@code PrecheckCommandInterceptor:93-109}). Same strictness as S21's upcaster registry,
   * same reason — a bean indexed under the interface would silently never run.
   *
   * <p>An anonymous class with a diamond is fine, and that was worth checking rather than assuming:
   * {@code ResolvableType.forInstance(...).as(CommandPrecheck.class).getGeneric(0)} resolves to
   * {@code PlaceOrder} for a named class, an anonymous class with {@code <>}, and an anonymous class with
   * the argument spelled out — and only to {@code Command} for a lambda. An earlier version of this file
   * claimed a named class was needed; it was not, and the failure that prompted the claim had another
   * cause entirely (see {@code RiskStubServer}'s executor).
   */
  @Bean
  CommandPrecheck<PlaceOrder> recordTransactionStateDuringPrecheck(Recorder recorder) {
    return new CommandPrecheck<>() {
      @Override
      public void check(PlaceOrder command, CommandContext context) {
        recorder.duringPrecheck.set(TransactionSynchronizationManager.isActualTransactionActive());
      }
    };
  }

  /** Order 400: innermost, so inside the transaction interceptor at 200. */
  @Bean
  @Order(400)
  CommandInterceptor recordTransactionStateInsideHandler(Recorder recorder) {
    return new CommandInterceptor() {
      @Override
      public <R> R intercept(Command<R> command, CommandContext context, Invocation<R> invocation) {
        recorder.insideHandler.set(TransactionSynchronizationManager.isActualTransactionActive());
        return invocation.proceed();
      }

      @Override
      public int order() {
        return 400;
      }
    };
  }
}
