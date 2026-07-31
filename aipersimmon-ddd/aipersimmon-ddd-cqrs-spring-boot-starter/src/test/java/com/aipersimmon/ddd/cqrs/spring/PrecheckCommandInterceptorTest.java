package com.aipersimmon.ddd.cqrs.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.aipersimmon.ddd.cqrs.CommandPrecheck;
import com.aipersimmon.ddd.cqrs.UnitOfWork;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * {@link PrecheckCommandInterceptor}: a {@link CommandPrecheck} runs for its command type only,
 * strictly before the transaction opens, and a refusal keeps the transaction from ever opening
 * (issue-00141). The ordering claim is the load-bearing one — a precheck that ran inside the
 * transaction would be exactly the connection-holding remote call the extension point exists to
 * prevent.
 */
class PrecheckCommandInterceptorTest {

  record Place(String id) implements Command<String> {}

  record Confirm(String id) implements Command<String> {}

  /** Marks the transaction boundary so a precheck can prove it ran outside. */
  static final class BoundaryUnitOfWork implements UnitOfWork {
    boolean inTransaction;
    boolean everEntered;

    @Override
    public <T> T execute(Supplier<T> work) {
      inTransaction = true;
      everEntered = true;
      try {
        return work.get();
      } finally {
        inTransaction = false;
      }
    }
  }

  static final class PlaceHandler implements CommandHandler<Place, String> {
    boolean handled;

    @Override
    public String handle(Place command, CommandContext context) {
      handled = true;
      return command.id();
    }
  }

  static final class ConfirmHandler implements CommandHandler<Confirm, String> {
    @Override
    public String handle(Confirm command, CommandContext context) {
      return command.id();
    }
  }

  static final class RecordingPlacePrecheck implements CommandPrecheck<Place> {
    private final BoundaryUnitOfWork unitOfWork;
    int calls;
    Boolean sawTransaction;
    RuntimeException refusal;

    RecordingPlacePrecheck(BoundaryUnitOfWork unitOfWork) {
      this.unitOfWork = unitOfWork;
    }

    @Override
    public void check(Place command, CommandContext context) {
      calls++;
      sawTransaction = unitOfWork.inTransaction;
      if (refusal != null) {
        throw refusal;
      }
    }
  }

  private final BoundaryUnitOfWork unitOfWork = new BoundaryUnitOfWork();
  private final RecordingPlacePrecheck precheck = new RecordingPlacePrecheck(unitOfWork);
  private final PlaceHandler placeHandler = new PlaceHandler();

  private CommandBus bus(CommandPrecheck<?>... prechecks) {
    return new RegistryCommandBus(
        List.of(placeHandler, new ConfirmHandler()),
        List.of(
            new PrecheckCommandInterceptor(List.of(prechecks)),
            new TransactionCommandInterceptor(unitOfWork)),
        () -> "cmd-1");
  }

  @Test
  void aPrecheckRunsOutsideTheTransactionAndBeforeTheHandler() {
    bus(precheck).send(new Place("p-1"));

    assertEquals(1, precheck.calls);
    assertFalse(
        precheck.sawTransaction,
        "the precheck must run before the transaction opens — that slot is its whole point");
    assertTrue(placeHandler.handled);
  }

  @Test
  void aRefusalKeepsTheTransactionFromEverOpening() {
    precheck.refusal = new IllegalStateException("inventory cannot offer these");

    assertThrows(IllegalStateException.class, () -> bus(precheck).send(new Place("p-1")));

    assertFalse(unitOfWork.everEntered, "a refused command must not cost a transaction");
    assertFalse(placeHandler.handled, "a refused command must not reach its handler");
  }

  @Test
  void aPrecheckScreensOnlyItsOwnCommandType() {
    bus(precheck).send(new Confirm("c-1"));

    assertEquals(0, precheck.calls, "a Confirm dispatch must not invoke a Place precheck");
  }

  @Test
  void everyPrecheckForTheTypeRunsAndTheFirstRefusalWins() {
    RecordingPlacePrecheck second = new RecordingPlacePrecheck(unitOfWork);
    precheck.refusal = new IllegalStateException("first refuses");

    assertThrows(IllegalStateException.class, () -> bus(precheck, second).send(new Place("p-1")));

    assertEquals(1, precheck.calls);
    assertEquals(0, second.calls, "prechecks after a refusal must not run — the dispatch is dead");
  }

  @Test
  void anUnresolvablePrecheckTypeFailsTheStartupForce() {
    // The type parameter is a method type variable, unresolvable from the instance — exactly the
    // mistake the startup force exists to catch before the first dispatch does.
    PrecheckCommandInterceptor interceptor = new PrecheckCommandInterceptor(List.of(erased()));

    IllegalStateException refused =
        assertThrows(IllegalStateException.class, interceptor::afterSingletonsInstantiated);
    assertTrue(refused.getMessage().contains("Cannot resolve the command type"));
  }

  private static <C extends Command<?>> CommandPrecheck<C> erased() {
    return new CommandPrecheck<C>() {
      @Override
      public void check(C command, CommandContext context) {}
    };
  }
}
