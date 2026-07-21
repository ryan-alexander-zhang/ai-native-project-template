package com.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.application.DurableIntegrationEvents;
import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandInterceptor;
import com.example.ordering.application.order.PlaceOrder;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * issue-00027 regression: the aggregate write and the outbox write are one transaction. A command
 * interceptor ordered <em>inside</em> the transaction boundary ({@code order > 200}) throws right
 * after the handler runs — so {@code PlaceOrderHandler} has already persisted the order (into
 * {@code ordering.orders}) and written its {@code OrderPlaced} row (into {@code
 * aipersimmon_outbox}) when the transaction rolls back. Both must be gone.
 *
 * <p>This assertion is only possible now that aggregates are transactional (plan-00007). With the
 * old in-memory {@code ConcurrentHashMap} the order would survive the rollback while the outbox row
 * vanished — the exact split-brain issue-00027 described.
 */
@SpringBootTest(
    properties = {
      "aipersimmon.ddd.process-manager.jdbc.effect-relay.poll-delay=1h",
      "aipersimmon.ddd.process-manager.jdbc.deadline-worker.poll-delay=1h",
      "aipersimmon.ddd.outbox.poll-delay-ms=3600000",
    })
@Import(TestInfrastructure.class)
class OutboxAtomicityTest {

  @Autowired CommandBus commandBus;

  @Autowired JdbcTemplate jdbc;

  @Autowired IntegrationEvents integrationEvents;

  @Test
  void aggregateAndOutboxRollBackTogetherWhenTheTransactionFails() {
    // Precondition (issue-00044): the OrderPlaced event is actually written to the outbox in the
    // command transaction — i.e. the active publisher is the durable outbox writer, not the
    // in-process fallback. Without this the "outbox == 0 after rollback" assertion below is
    // vacuously true (nothing is ever written) and proves nothing about atomicity.
    assertInstanceOf(
        DurableIntegrationEvents.class,
        integrationEvents,
        "active IntegrationEvents must be the durable outbox writer for this test to be meaningful");

    // CUST-1 / SKU-1 x1 is valid and in stock, so the handler runs to completion — it saves the
    // order and writes the OrderPlaced outbox row — before the interceptor throws inside the tx.
    assertThrows(
        RuntimeException.class,
        () ->
            commandBus.send(
                new PlaceOrder("CUST-1", List.of(new PlaceOrder.Line("SKU-1", 1, 100, "USD")))));

    Long orders = jdbc.queryForObject("select count(*) from ordering.orders", Long.class);
    Long outbox = jdbc.queryForObject("select count(*) from aipersimmon_outbox", Long.class);
    assertEquals(0L, orders, "the order must roll back with the failed transaction");
    assertEquals(0L, outbox, "the outbox row must roll back with the failed transaction");
  }

  /**
   * Throws after the handler, ordered inside the transaction boundary
   * (TransactionCommandInterceptor = 200).
   */
  @TestConfiguration
  static class FailInsideTransaction {
    @Bean
    CommandInterceptor failAfterHandler() {
      return new CommandInterceptor() {
        @Override
        public <R> R intercept(
            Command<R> command, CommandContext context, Invocation<R> invocation) {
          R result = invocation.proceed();
          if (command instanceof PlaceOrder) {
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
