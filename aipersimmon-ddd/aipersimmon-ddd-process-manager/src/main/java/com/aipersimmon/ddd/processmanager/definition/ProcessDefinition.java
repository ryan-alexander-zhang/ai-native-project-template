package com.aipersimmon.ddd.processmanager.definition;

import com.aipersimmon.ddd.processmanager.model.DefinitionVersion;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.aipersimmon.ddd.processmanager.model.StateSchemaVersion;

/**
 * A consumer's process, expressed as a pure, deterministic decision object: given the current
 * state, an input, and a read-only {@link ProcessContext}, it returns a {@link ProcessDecision}. It
 * must do no I/O — no repository, HTTP, command bus, integration-event publish, system clock,
 * randomness, Spring bean, or third-party SDK — so it is fully unit-testable and safely replayable.
 *
 * <p>{@code start} handles only a new instance (context lifecycle/step empty); {@code react}
 * handles only an existing one (both present). It never mutates the state passed in; it returns a
 * new state inside the decision. Effect ids are not created here — the runtime derives them from
 * {@code transitionId + effectIndex}.
 *
 * <p>Several versions of one {@link #processType()} may be registered at once, but exactly one has
 * {@link #activeForNewInstances()} true; older versions stay registered (with it false) to keep
 * serving their running instances.
 *
 * @param <S> the business state type this definition reads and returns
 */
public interface ProcessDefinition<S> {

  /** The logical process type this definition implements. */
  ProcessType processType();

  /** This definition's version; a running instance is pinned to it. */
  DefinitionVersion definitionVersion();

  /** Whether new instances of this type start on this version (exactly one true per type). */
  boolean activeForNewInstances();

  /** The schema version of the state this definition reads and writes. */
  StateSchemaVersion stateSchemaVersion();

  /**
   * Decide the first transition of a new instance.
   *
   * @param input the starting input
   * @param context the decision context; its current lifecycle/step are empty
   * @return the decision, including the initial state and any effects
   */
  ProcessDecision<S> start(ProcessInput input, ProcessContext context);

  /**
   * Decide the next transition of an existing instance.
   *
   * @param currentState the instance's current decoded state
   * @param input the input to react to
   * @param context the decision context; its current lifecycle/step are present
   * @return the decision, including the new state and any effects
   */
  ProcessDecision<S> react(S currentState, ProcessInput input, ProcessContext context);
}
