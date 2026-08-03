package com.example.samples.s19.ordering.application;

import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandPrecheck;
import com.example.samples.s19.ordering.domain.OrderingErrorCode;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Layer three of the three, and the one with no obvious home until the library gave it one.
 *
 * <p>It answers a question this context cannot answer itself, by asking another one. Put this on the
 * handler's first line and it runs inside the write transaction — so the moment the port behind it is a
 * remote client, a database connection sits idle waiting on the network, and one slow dependency turns
 * into an exhausted pool. Running here, between validation and the transaction interceptor, a refusal
 * costs no connection at all. {@code ValidationLayersTest} asserts that placement rather than trusting
 * it.
 *
 * <p>It reads and refuses, nothing else: a write here would live outside the command's transaction and
 * survive its rollback. And it runs on every dispatch, including at-least-once redeliveries, so it has
 * to be safe to repeat.
 */
@Component
@Order(10)
class CustomerMustNotBeBlocked implements CommandPrecheck<PlaceOrder> {

  private final CustomerStanding standing;

  CustomerMustNotBeBlocked(CustomerStanding standing) {
    this.standing = standing;
  }

  @Override
  public void check(PlaceOrder command, CommandContext context) {
    if (standing.isBlocked(command.customerId())) {
      throw new DomainException(
          OrderingErrorCode.CUSTOMER_BLOCKED, "customer " + command.customerId() + " is blocked");
    }
  }
}
