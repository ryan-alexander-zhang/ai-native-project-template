package com.aipersimmon.ddd.processmanager.definition;

import com.aipersimmon.ddd.processmanager.effect.CancelDeadline;
import com.aipersimmon.ddd.processmanager.effect.ProcessEffect;
import com.aipersimmon.ddd.processmanager.effect.ScheduleDeadline;
import com.aipersimmon.ddd.processmanager.model.DeadlineName;
import com.aipersimmon.ddd.processmanager.model.DecisionCode;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessOutcome;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The result a {@link ProcessDefinition} returns for one input: the new business {@code state}, the
 * target {@code lifecycle} and {@code step}, an {@code outcome} when terminal, a {@code
 * decisionCode} for audit, and the ordered {@code effects} to perform after commit.
 *
 * <p>This record enforces the decision invariants that do not need runtime context:
 *
 * <ul>
 *   <li>a definition may not return {@link ProcessLifecycle#SUSPENDED} — that is an operational
 *       state only the runtime sets;
 *   <li>a terminal lifecycle must carry an outcome, and a non-terminal one must not;
 *   <li>a deadline name may appear in at most one deadline effect, so scheduling and cancelling the
 *       same name in one decision is not ambiguous.
 * </ul>
 *
 * The remaining check — that the lifecycle transition itself is legal — needs the current lifecycle
 * and is performed by the runtime. Effects are defensively copied and their order is preserved, so
 * replaying the same input yields the same effects.
 *
 * @param <S> the business state type
 */
public record ProcessDecision<S>(
    S state,
    ProcessLifecycle lifecycle,
    ProcessStep step,
    Optional<ProcessOutcome> outcome,
    DecisionCode decisionCode,
    List<ProcessEffect> effects) {

  public ProcessDecision {
    if (state == null) {
      throw new IllegalArgumentException("state required");
    }
    if (lifecycle == null) {
      throw new IllegalArgumentException("lifecycle required");
    }
    if (lifecycle == ProcessLifecycle.SUSPENDED) {
      throw new IllegalArgumentException(
          "a ProcessDefinition must not return SUSPENDED; it is set by the runtime "
              + "on retry exhaustion. A business wait stays RUNNING with a business step.");
    }
    if (step == null) {
      throw new IllegalArgumentException("step required");
    }
    if (outcome == null) {
      throw new IllegalArgumentException("outcome optional required (use Optional.empty())");
    }
    if (lifecycle.isTerminal() && outcome.isEmpty()) {
      throw new IllegalArgumentException(
          "a terminal lifecycle (" + lifecycle + ") must carry an outcome");
    }
    if (!lifecycle.isTerminal() && outcome.isPresent()) {
      throw new IllegalArgumentException(
          "a non-terminal lifecycle (" + lifecycle + ") must not carry an outcome");
    }
    if (decisionCode == null) {
      throw new IllegalArgumentException("decisionCode required");
    }
    if (effects == null) {
      throw new IllegalArgumentException("effects required (use an empty list)");
    }
    effects = List.copyOf(effects);
    requireUnambiguousDeadlines(effects);
  }

  private static void requireUnambiguousDeadlines(List<ProcessEffect> effects) {
    Set<DeadlineName> seen = new HashSet<>();
    for (ProcessEffect effect : effects) {
      DeadlineName name =
          switch (effect) {
            case ScheduleDeadline schedule -> schedule.name();
            case CancelDeadline cancel -> cancel.name();
            default -> null;
          };
      if (name != null && !seen.add(name)) {
        throw new IllegalArgumentException(
            "ambiguous deadline operations for '"
                + name.value()
                + "' in one decision: a deadline name may appear in at most one effect");
      }
    }
  }
}
