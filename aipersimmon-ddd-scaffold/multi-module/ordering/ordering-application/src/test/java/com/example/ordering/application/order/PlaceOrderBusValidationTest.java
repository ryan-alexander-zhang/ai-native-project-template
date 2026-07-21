package com.example.ordering.application.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * BC-level test of the command-bus validation gate — assembled by hand from the real {@link
 * RegistryCommandBus} and {@link ValidationCommandInterceptor}, with no Spring context. It proves
 * that this context's {@link PlaceOrder} constraints are enforced for <em>every</em> caller of the
 * bus (not just the web adapter): a malformed command is rejected before its handler runs, and a
 * well-formed one reaches the handler.
 */
class PlaceOrderBusValidationTest {

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
  private final RecordingHandler handler = new RecordingHandler();
  private final CommandBus bus =
      new RegistryCommandBus(
          List.of(handler), List.of(new ValidationCommandInterceptor(validator)));

  @Test
  void malformedCommandIsRejectedBeforeTheHandlerRuns() {
    PlaceOrder malformed = new PlaceOrder("", List.of());

    assertThrows(ConstraintViolationException.class, () -> bus.send(malformed));
    assertFalse(handler.invoked, "handler must not run for an invalid command");
  }

  @Test
  void wellFormedCommandReachesTheHandler() {
    PlaceOrder valid =
        new PlaceOrder("cust-1", List.of(new PlaceOrder.Line("SKU-1", 2, 1500, "USD")));

    String result = bus.send(valid);

    assertTrue(handler.invoked, "handler must run for a valid command");
    assertEquals("ord-1", result);
  }

  /** Concrete handler so the bus can resolve the command type from its generics. */
  private static final class RecordingHandler implements CommandHandler<PlaceOrder, String> {
    private boolean invoked;

    @Override
    public String handle(PlaceOrder command, CommandContext context) {
      invoked = true;
      return "ord-1";
    }
  }
}
