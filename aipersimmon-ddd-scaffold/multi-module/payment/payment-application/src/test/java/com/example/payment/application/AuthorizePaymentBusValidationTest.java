package com.example.payment.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.aipersimmon.ddd.cqrs.spring.RegistryCommandBus;
import com.aipersimmon.ddd.cqrs.spring.ValidationCommandInterceptor;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The amount range this context accepts, enforced at its own entry. {@link AuthorizePayment}
 * arrives from an integration-event listener rather than from HTTP, so the command bus's validation
 * gate is the only thing standing between ordering's published {@code PaymentRequested} and this
 * handler — and a violation here is not a 400 to a caller, it is a poisoned message that retries
 * until it dead-letters while the ordering flow waits.
 *
 * <p>Assembled by hand from the real {@link RegistryCommandBus} and {@link
 * ValidationCommandInterceptor}, matching {@code PlaceOrderBusValidationTest} on the ordering side.
 * The two together are what keeps the ranges reconcilable: ordering accepts a zero-amount line, so
 * payment must accept a zero-amount authorization.
 */
class AuthorizePaymentBusValidationTest {

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
  private final RecordingHandler handler = new RecordingHandler();
  private final CommandBus bus =
      new RegistryCommandBus(
          List.of(handler), List.of(new ValidationCommandInterceptor(validator)), () -> "cmd-1");

  @Test
  void aZeroAmountAuthorizationReachesTheHandler() {
    // A gift line or a fully discounted basket totals zero. Ordering lets one through
    // (@PositiveOrZero on PlaceOrder.Line), so rejecting it here would strand the order in
    // AWAITING_PAYMENT until the deadline cancelled it as PAYMENT_TIMEOUT — a cancellation
    // reason with nothing to do with the real cause.
    bus.send(new AuthorizePayment("order-1", "op-1", 0L, "USD"));

    assertTrue(handler.invoked, "zero is inside the range this context accepts");
  }

  @Test
  void aNegativeAmountIsRejectedBeforeTheHandlerRuns() {
    AuthorizePayment negative = new AuthorizePayment("order-1", "op-1", -1L, "USD");

    assertThrows(ConstraintViolationException.class, () -> bus.send(negative));
    assertFalse(handler.invoked, "widening the range to zero must not widen it below zero");
  }

  @Test
  void aMissingOperationIdIsRejectedBeforeTheHandlerRuns() {
    AuthorizePayment unkeyed = new AuthorizePayment("order-1", "", 100L, "USD");

    assertThrows(ConstraintViolationException.class, () -> bus.send(unkeyed));
    assertFalse(handler.invoked, "without an operation id there is nothing to dedupe by");
  }

  /** Concrete handler so the bus can resolve the command type from its generics. */
  private static final class RecordingHandler implements CommandHandler<AuthorizePayment, Void> {
    private boolean invoked;

    @Override
    public Void handle(AuthorizePayment command, CommandContext context) {
      invoked = true;
      return null;
    }
  }
}
