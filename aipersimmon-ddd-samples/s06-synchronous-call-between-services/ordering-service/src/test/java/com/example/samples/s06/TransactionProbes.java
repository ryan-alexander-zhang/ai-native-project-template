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
   * A named class, not a lambda and not an anonymous one.
   *
   * <p>Prechecks are indexed by their type parameter, so a lambda is refused at startup ("Cannot resolve
   * the command type of precheck ...") — the same strictness the upcaster registry applies in S21, and for
   * the same reason: a bean indexed under the interface would silently never run. An anonymous class with
   * a diamond gets past that check and then <em>did not run</em> here either, which is the more
   * interesting half: the resolution reads the instance's generic supertype, and the surest way to give it
   * one is to write the type out.
   */
  static final class TransactionStateProbe implements CommandPrecheck<PlaceOrder> {

    private final Recorder recorder;

    TransactionStateProbe(Recorder recorder) {
      this.recorder = recorder;
    }

    @Override
    public void check(PlaceOrder command, CommandContext context) {
      recorder.duringPrecheck.set(TransactionSynchronizationManager.isActualTransactionActive());
    }
  }

  @Bean
  TransactionStateProbe recordTransactionStateDuringPrecheck(Recorder recorder) {
    return new TransactionStateProbe(recorder);
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
