package com.aipersimmon.ddd.archunit.fixture.bad.ordering.application;

import com.aipersimmon.ddd.operationlog.annotation.OperationLog;

/**
 * Violates {@link
 * com.aipersimmon.ddd.archunit.OperationLogRules#operationLogShouldOnlyAnnotateApplicationCommands()}:
 * an application-layer type that carries {@code @OperationLog} but is not a {@code Command}, so the
 * synthesized definition would have no command to project over. Exercises the rule's "implement
 * Command" branch specifically (it resides in the application layer, so only the command-type
 * requirement is broken).
 */
@OperationLog(code = "ordering.badNonCommand", targetType = "Order", targetId = "x", success = "y")
public final class BadOperationLogOnNonCommand {

  public void confirm(String orderId) {
    // A service method, not a command — the annotation has nothing to bind to.
  }
}
