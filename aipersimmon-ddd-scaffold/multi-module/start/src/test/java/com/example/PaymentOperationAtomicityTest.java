package com.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandInterceptor;
import com.example.payment.application.AuthorizePayment;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Claiming a payment operation and announcing its outcome are one commit.
 *
 * <p>The operation log used to be a {@code ConcurrentHashMap}, described as the lightest honest
 * dedupe for a scaffold with no payment datastore. The description misidentified what the pattern
 * needs. A {@code putIfAbsent} cannot be rolled back, so a transaction that claimed an operation
 * and then failed left the claim behind while the outcome event went with the rollback — and every
 * redelivery afterwards saw an operation already handled and published nothing at all. The
 * authorization was lost silently and permanently, with the payment deadline as the only thing
 * standing between that and an order stuck forever.
 *
 * <p>The point easily missed: <strong>durability was never the fix</strong>. An ordinary table
 * written in its own transaction has precisely the same hole. What this asserts is
 * co-transactionality — the claim has to die with the publish — which is why the log is a table on
 * the outbox's own {@code DataSource} rather than merely a table.
 *
 * <p>Relays are off so nothing drains the outbox mid-assertion.
 */
@SpringBootTest(
    properties = {
      "aipersimmon.ddd.process-manager.effect-relay.enabled=false",
      "aipersimmon.ddd.process-manager.deadline-worker.enabled=false",
      "aipersimmon.ddd.outbox.relay.enabled=false",
    })
@Import({TestInfrastructure.class, PaymentOperationAtomicityTest.FailInsideTransaction.class})
@ExtendWith(BoundTenant.class)
class PaymentOperationAtomicityTest {

  @Autowired CommandBus commandBus;

  @Autowired JdbcTemplate jdbc;

  @Test
  void aClaimDoesNotSurviveARolledBackTransaction() {
    String operation = "op-rollback-" + System.nanoTime();

    // First delivery: the handler records the claim and writes its outcome to the outbox, then an
    // interceptor inside the transaction boundary throws.
    FailInsideTransaction.ARMED.set(true);
    assertThrows(RuntimeException.class, () -> authorize(operation));

    assertEquals(
        0,
        claimsFor(operation),
        "the claim must roll back with the transaction that made it — surviving is what used to"
            + " make every later redelivery a silent no-op");
    assertEquals(0, outboxRowsFor(operation), "and so must the outcome event");

    // The redelivery an at-least-once transport would make. It has to be able to authorise, which
    // it can only do if the failed attempt left nothing behind.
    FailInsideTransaction.ARMED.set(false);
    authorize(operation);

    assertEquals(1, claimsFor(operation), "the redelivery authorises for real");
    assertEquals(1, outboxRowsFor(operation), "and announces the outcome that was lost");
  }

  @Test
  void aRedeliveryRepublishesTheRecordedOutcome() {
    String operation = "op-redeliver-" + System.nanoTime();

    authorize(operation);
    authorize(operation);

    assertEquals(
        1, claimsFor(operation), "the irreversible act happened once — the log did its job");
    assertEquals(
        2,
        outboxRowsFor(operation),
        "but the outcome is announced again: at-least-once delivery means the first announcement"
            + " may never have arrived, and the reader ignores a duplicate anyway");
  }

  private void authorize(String operationId) {
    commandBus.send(new AuthorizePayment("order-" + operationId, operationId, 100, "USD"));
  }

  private int claimsFor(String operationId) {
    Integer rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM payment.payment_operations WHERE operation_id = ?",
            Integer.class,
            operationId);
    return rows == null ? 0 : rows;
  }

  /** The outcome events this operation put in the outbox; the relay is off, so they stay. */
  private int outboxRowsFor(String operationId) {
    Integer rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM aipersimmon_outbox WHERE payload LIKE ?",
            Integer.class,
            "%order-" + operationId + "%");
    return rows == null ? 0 : rows;
  }

  /**
   * Throws after the handler but inside the transaction boundary (TransactionCommandInterceptor is
   * 200, so 300 is within it) — the same shape {@code OutboxAtomicityTest} uses. Armed per test so
   * the second delivery can succeed in the same context.
   */
  @TestConfiguration
  static class FailInsideTransaction {

    static final AtomicBoolean ARMED = new AtomicBoolean();

    @Bean
    CommandInterceptor failAfterAuthorize() {
      return new CommandInterceptor() {
        @Override
        public <R> R intercept(
            Command<R> command, CommandContext context, Invocation<R> invocation) {
          R result = invocation.proceed();
          if (command instanceof AuthorizePayment && ARMED.get()) {
            throw new IllegalStateException("boom after handler, inside the transaction");
          }
          return result;
        }

        @Override
        public int order() {
          return 300;
        }
      };
    }
  }
}
