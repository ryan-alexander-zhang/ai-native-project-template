package com.aipersimmon.ddd.processmanager.effect;

import com.aipersimmon.ddd.processmanager.definition.ProcessInput;
import com.aipersimmon.ddd.processmanager.model.DeadlineName;
import java.time.Instant;

/**
 * Effect asking the runtime to arm (or reschedule) the named timer {@code name} to fire at {@code
 * dueAt}. When it fires, the runtime turns {@code input} into an ordinary {@link ProcessInput} and
 * feeds it back through {@code handle} — a deadline is not a separate callback. Rescheduling the
 * same name bumps its generation so a late fire of an old generation is a no-op.
 *
 * @param name the timer name; non-null
 * @param dueAt when the timer should fire; non-null
 * @param input the input to deliver on firing; non-null
 */
public record ScheduleDeadline(DeadlineName name, Instant dueAt, ProcessInput input)
    implements ProcessEffect {

  public ScheduleDeadline {
    if (name == null) {
      throw new IllegalArgumentException("deadline name required");
    }
    if (dueAt == null) {
      throw new IllegalArgumentException("dueAt required");
    }
    if (input == null) {
      throw new IllegalArgumentException("deadline input required");
    }
  }

  @Override
  public ProcessEffectKind kind() {
    return ProcessEffectKind.SCHEDULE_DEADLINE;
  }
}
