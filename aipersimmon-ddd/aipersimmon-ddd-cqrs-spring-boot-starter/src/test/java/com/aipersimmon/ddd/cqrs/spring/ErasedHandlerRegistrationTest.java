package com.aipersimmon.ddd.cqrs.spring;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.aipersimmon.ddd.cqrs.Query;
import com.aipersimmon.ddd.cqrs.QueryHandler;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A handler whose command/query type parameter is erased resolves to its bound — the {@code
 * Command}/{@code Query} interface — and dispatch matches by the exact class, so such a
 * registration would never receive a dispatch. It used to "register" successfully and every real
 * send then failed with the misleading "No handler registered"; the precheck registry already
 * refused it at startup, and both buses now apply the same strictness.
 */
class ErasedHandlerRegistrationTest {

  @Test
  void anErasedCommandHandlerFailsTheStartupForceInsteadOfRegisteringUnreachably() {
    RegistryCommandBus bus =
        new RegistryCommandBus(List.of(erasedCommandHandler()), List.of(), () -> "id-1");

    IllegalStateException refused =
        assertThrows(IllegalStateException.class, bus::afterSingletonsInstantiated);
    assertTrue(refused.getMessage().contains("Cannot resolve the command type"));
    assertTrue(
        refused.getMessage().contains("never receive a dispatch"),
        "the message must explain the silent-never-matches hazard: " + refused.getMessage());
  }

  @Test
  void anErasedQueryHandlerFailsTheStartupForceInsteadOfRegisteringUnreachably() {
    RegistryQueryBus bus = new RegistryQueryBus(List.of(erasedQueryHandler()));

    IllegalStateException refused =
        assertThrows(IllegalStateException.class, bus::afterSingletonsInstantiated);
    assertTrue(refused.getMessage().contains("Cannot resolve the query type"));
  }

  // The type parameter is a method type variable: from the instance it resolves only to its
  // bound, which is the interface itself — exactly the registration the buses must refuse.
  private static <C extends Command<String>> CommandHandler<C, String> erasedCommandHandler() {
    return new CommandHandler<C, String>() {
      @Override
      public String handle(C command, CommandContext context) {
        return "unreachable";
      }
    };
  }

  private static <Q extends Query<String>> QueryHandler<Q, String> erasedQueryHandler() {
    return new QueryHandler<Q, String>() {
      @Override
      public String handle(Q query) {
        return "unreachable";
      }
    };
  }
}
