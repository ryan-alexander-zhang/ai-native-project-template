package com.example.ordering.application.order;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.aipersimmon.ddd.cqrs.spring.RegistryCommandBus;
import com.aipersimmon.ddd.cqrs.spring.ValidationCommandInterceptor;
import com.example.ordering.domain.customer.CustomerId;
import com.example.ordering.domain.order.CancellationReason;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link PlaceOrderBusValidationTest}'s counterpart for an <em>internal</em> command — one no web
 * adapter ever binds. {@link CancelOrder} arrives from the fulfilment process manager's relay, so
 * the bus's validation gate is the only validation it will ever meet; if its constraints are
 * missing, a malformed cancel walks straight into the handler and fails deep inside the aggregate
 * as a runtime null check instead of at the door as a validation error. The bus promises the gate
 * for every entry, and this test holds an internal command to that promise.
 */
class CancelOrderBusValidationTest {

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
  private final RecordingHandler handler = new RecordingHandler();
  private final CommandBus bus =
      new RegistryCommandBus(
          List.of(handler), List.of(new ValidationCommandInterceptor(validator)), () -> "cmd-1");

  @Test
  void malformedCommandIsRejectedBeforeTheHandlerRuns() {
    CancelOrder malformed = new CancelOrder(null, null);

    assertThrows(ConstraintViolationException.class, () -> bus.send(malformed));
    assertFalse(handler.invoked, "handler must not run for an invalid command");
  }

  @Test
  void wellFormedCommandReachesTheHandler() {
    CancelOrder valid =
        new CancelOrder(
            "ord-1", new CancellationReason.CustomerRequested(new CustomerId("cust-1")));

    bus.send(valid);

    assertTrue(handler.invoked, "handler must run for a valid command");
  }

  /** Concrete handler so the bus can resolve the command type from its generics. */
  private static final class RecordingHandler implements CommandHandler<CancelOrder, Void> {
    private boolean invoked;

    @Override
    public Void handle(CancelOrder command, CommandContext context) {
      invoked = true;
      return null;
    }
  }
}
