package com.aipersimmon.ddd.processmanager.definition;

import com.aipersimmon.ddd.processmanager.model.DefinitionVersion;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.aipersimmon.ddd.processmanager.model.StateSchemaVersion;
import java.util.Set;

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
 * <p>A first process implements three methods — {@link #processType()}, {@link #start} and {@link
 * #react}. Versioning ({@link #definitionVersion()}, {@link #activeForNewInstances()}, {@link
 * #stateSchemaVersion()}) is defaulted to the single-version case, so its concepts appear only once
 * a flow actually needs a second version. Nothing about the engine is weakened by that: the
 * defaults are the values a one-version flow would have written by hand, and the registry still
 * refuses to start if two versions collide or none is active.
 *
 * @param <S> the business state type this definition reads and returns
 */
public interface ProcessDefinition<S> {

  /** The logical process type this definition implements. */
  ProcessType processType();

  /**
   * This definition's version; a running instance is pinned to it. Defaults to {@code v1}: a
   * process has exactly one version until it needs a second, and writing {@code v1} by hand carries
   * no information. Override on every version once there is more than one — a second definition
   * that forgets to is rejected at startup ({@code two process definitions registered for <type>
   * v1}), so the default cannot silently shadow anything.
   */
  default DefinitionVersion definitionVersion() {
    return DefinitionVersion.INITIAL;
  }

  /**
   * Whether new instances of this type start on this version (exactly one true per type). Defaults
   * to {@code true}, which is right while there is one version and loudly wrong when a second one
   * forgets to override it: the registry rejects startup with {@code more than one active
   * definition for process type <type>}. Set it to {@code false} on the old version when you
   * introduce a new one — the old version stays registered to keep serving its running instances.
   */
  default boolean activeForNewInstances() {
    return true;
  }

  /**
   * The schema version of the state this definition reads and writes. Defaults to the first
   * version. Bump it — and register a codec for the new schema — whenever the state's shape changes
   * in a way an older encoded state cannot satisfy; unlike the two above, nothing can detect a
   * forgotten bump for you, because a stale state simply decodes into the wrong shape.
   */
  default StateSchemaVersion stateSchemaVersion() {
    return StateSchemaVersion.INITIAL;
  }

  /**
   * Every payload class this definition can receive as an input or stage as an effect — so a
   * forgotten codec registration fails the <em>startup</em>, not the first advance that happens to
   * encode it inside somebody's transaction. The startup validator reconciles each declared class
   * against the payload codec registry and refuses to start naming whatever is missing.
   *
   * <p>Defaults to an empty set, which means "not validated": declaring is opt-in, because only the
   * definition's author knows the full set. A flow that declares gets fail-fast; one that does not
   * keeps today's behaviour.
   */
  default Set<Class<?>> declaredPayloads() {
    return Set.of();
  }

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
