package com.aipersimmon.ddd.processmanager.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The lifecycle state machine's transition rules. */
class ProcessLifecycleTest {

  @Test
  void terminalStatesAreTerminalAndInactive() {
    for (ProcessLifecycle terminal :
        EnumSet.of(
            ProcessLifecycle.COMPLETED, ProcessLifecycle.FAILED, ProcessLifecycle.CANCELLED)) {
      assertTrue(terminal.isTerminal(), terminal + " should be terminal");
      assertFalse(terminal.isActive(), terminal + " should be inactive");
    }
    for (ProcessLifecycle active :
        EnumSet.of(
            ProcessLifecycle.RUNNING, ProcessLifecycle.COMPENSATING, ProcessLifecycle.SUSPENDED)) {
      assertFalse(active.isTerminal(), active + " should not be terminal");
      assertTrue(active.isActive(), active + " should be active");
    }
  }

  @Test
  void runningMayAdvanceCompensateSuspendOrEnd() {
    assertLegal(
        ProcessLifecycle.RUNNING,
        EnumSet.of(
            ProcessLifecycle.RUNNING,
            ProcessLifecycle.COMPENSATING,
            ProcessLifecycle.SUSPENDED,
            ProcessLifecycle.COMPLETED,
            ProcessLifecycle.FAILED,
            ProcessLifecycle.CANCELLED));
  }

  @Test
  void compensatingMayNotReturnToRunning() {
    assertLegal(
        ProcessLifecycle.COMPENSATING,
        EnumSet.of(
            ProcessLifecycle.COMPENSATING,
            ProcessLifecycle.SUSPENDED,
            ProcessLifecycle.COMPLETED,
            ProcessLifecycle.FAILED,
            ProcessLifecycle.CANCELLED));
    assertFalse(ProcessLifecycle.COMPENSATING.canTransitionTo(ProcessLifecycle.RUNNING));
  }

  @Test
  void suspendedResumesForwardOrCompensatingOrCancels() {
    assertLegal(
        ProcessLifecycle.SUSPENDED,
        EnumSet.of(
            ProcessLifecycle.RUNNING, ProcessLifecycle.COMPENSATING, ProcessLifecycle.CANCELLED));
    assertFalse(ProcessLifecycle.SUSPENDED.canTransitionTo(ProcessLifecycle.COMPLETED));
  }

  @Test
  void terminalStatesPermitNoTransition() {
    for (ProcessLifecycle terminal :
        EnumSet.of(
            ProcessLifecycle.COMPLETED, ProcessLifecycle.FAILED, ProcessLifecycle.CANCELLED)) {
      for (ProcessLifecycle target : ProcessLifecycle.values()) {
        assertFalse(
            terminal.canTransitionTo(target), terminal + " -> " + target + " must be illegal");
      }
    }
  }

  private static void assertLegal(ProcessLifecycle from, Set<ProcessLifecycle> legal) {
    for (ProcessLifecycle target : ProcessLifecycle.values()) {
      boolean allowed = from.canTransitionTo(target);
      if (legal.contains(target)) {
        assertTrue(allowed, from + " -> " + target + " should be legal");
      } else {
        assertFalse(allowed, from + " -> " + target + " should be illegal");
      }
    }
  }
}
