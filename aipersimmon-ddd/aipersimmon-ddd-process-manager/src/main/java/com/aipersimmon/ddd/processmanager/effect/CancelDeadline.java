package com.aipersimmon.ddd.processmanager.effect;

import com.aipersimmon.ddd.processmanager.model.DeadlineName;

/**
 * Effect asking the runtime to cancel the current generation of the named timer {@code name}. It
 * does not cancel a generation armed by a later {@link ScheduleDeadline}.
 *
 * @param name the timer name to cancel; non-null
 */
public record CancelDeadline(DeadlineName name) implements ProcessEffect {

  public CancelDeadline {
    if (name == null) {
      throw new IllegalArgumentException("deadline name required");
    }
  }

  @Override
  public ProcessEffectKind kind() {
    return ProcessEffectKind.CANCEL_DEADLINE;
  }
}
