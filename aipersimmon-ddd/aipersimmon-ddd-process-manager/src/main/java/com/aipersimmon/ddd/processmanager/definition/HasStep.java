package com.aipersimmon.ddd.processmanager.definition;

import com.aipersimmon.ddd.processmanager.model.ProcessStep;

/**
 * An optional contract for a process state that knows which business step it is at. Implementing it
 * buys two things:
 *
 * <ul>
 *   <li>the {@link ProcessDecision} factories ({@code running} / {@code compensating} / {@code
 *       completed}) read the step from the state, so a decision states it once instead of twice —
 *       once inside the state and once as a parameter that had better agree;
 *   <li>{@link ProcessDecision}'s constructor verifies any explicitly-passed step against the
 *       state's own, so the persisted step column and the step inside the encoded state can no
 *       longer drift apart silently.
 * </ul>
 *
 * <p>The method is named {@code processStep} rather than {@code step} so a state record whose own
 * step is a domain enum (the common case) can keep {@code step()} as its component accessor and
 * implement this by wrapping: {@code new ProcessStep(step().name())}.
 */
public interface HasStep {

  /** The business step this state is waiting at, as the runtime persists it. */
  ProcessStep processStep();
}
