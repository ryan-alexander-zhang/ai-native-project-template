package com.aipersimmon.ddd.cqrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.tenancy.Tenants;
import org.junit.jupiter.api.Test;

/**
 * The ambient dispatch scope (issue-00137). The bus binds the context it dispatches under so that a
 * synchronous domain-event subscriber — which sits on the handler's call stack but receives no
 * {@code CommandContext} parameter — can continue the causal chain instead of fabricating a new
 * one. The scope must restore, not clear: a handler that sends a follow-up command nests a second
 * dispatch inside the first, and the outer context must survive the inner one.
 */
class CommandContextsTest {

  private static final CommandContext OUTER = CommandContext.root(Tenants.ROOT, "outer-1");
  private static final CommandContext INNER = OUTER.deriveChild("inner-2");

  @Test
  void nothingIsBoundOutsideADispatch() {
    assertTrue(CommandContexts.current().isEmpty());
  }

  @Test
  void theContextIsReadableInsideTheScopeAndGoneAfter() {
    CommandContext seen =
        CommandContexts.runAs(OUTER, () -> CommandContexts.current().orElseThrow());

    assertEquals(OUTER, seen);
    assertTrue(CommandContexts.current().isEmpty(), "the scope must not outlive the dispatch");
  }

  @Test
  void aNestedScopeRestoresTheOuterContextNotEmptiness() {
    CommandContexts.runAs(
        OUTER,
        () -> {
          CommandContext seenInside =
              CommandContexts.runAs(INNER, () -> CommandContexts.current().orElseThrow());
          assertEquals(INNER, seenInside);
          assertEquals(
              OUTER,
              CommandContexts.current().orElseThrow(),
              "the inner dispatch must hand the outer one its context back");
          return null;
        });
    assertTrue(CommandContexts.current().isEmpty());
  }

  @Test
  void aThrowingBodyStillUnbinds() {
    RuntimeException boom = new RuntimeException("boom");

    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                CommandContexts.runAs(
                    OUTER,
                    () -> {
                      throw boom;
                    }));

    assertEquals(boom, thrown);
    assertTrue(
        CommandContexts.current().isEmpty(),
        "a failed dispatch must not leak its context onto the thread");
  }

  @Test
  void aNullContextIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> CommandContexts.runAs(null, () -> null));
  }
}
