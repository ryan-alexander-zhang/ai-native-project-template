package com.example.inventory.application.stock;

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
 * BC-level test of the command-bus validation gate for inventory — assembled by hand from the real
 * {@link RegistryCommandBus} and {@link ValidationCommandInterceptor}, with no Spring context.
 * Inventory has no web adapter: {@link ReserveStock} enters via an integration-event listener, so
 * the bus is the only place its constraints can be enforced. A malformed command is rejected before
 * its handler runs.
 */
class ReserveStockBusValidationTest {

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
  private final RecordingHandler handler = new RecordingHandler();
  private final CommandBus bus =
      new RegistryCommandBus(
          List.of(handler), List.of(new ValidationCommandInterceptor(validator)));

  @Test
  void malformedCommandIsRejectedBeforeTheHandlerRuns() {
    ReserveStock malformed = new ReserveStock("", List.of());

    assertThrows(ConstraintViolationException.class, () -> bus.send(malformed));
    assertFalse(handler.invoked, "handler must not run for an invalid command");
  }

  @Test
  void wellFormedCommandReachesTheHandler() {
    ReserveStock valid = new ReserveStock("ord-1", List.of(new ReserveStock.Line("SKU-1", 3)));

    bus.send(valid);

    assertTrue(handler.invoked, "handler must run for a valid command");
  }

  /** Concrete handler so the bus can resolve the command type from its generics. */
  private static final class RecordingHandler implements CommandHandler<ReserveStock, Void> {
    private boolean invoked;

    @Override
    public Void handle(ReserveStock command, CommandContext context) {
      invoked = true;
      return null;
    }
  }
}
