package com.aipersimmon.ddd.processmanager.definition;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.model.DefinitionVersion;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessRef;
import com.aipersimmon.ddd.processmanager.model.ProcessRevision;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;
import java.time.Instant;
import java.util.Optional;

/**
 * The read-only context the runtime hands a {@link ProcessDefinition} for one decision. Supplying
 * {@code now}, the process reference, and the causing {@link CommandContext} from the runtime
 * (rather than letting the definition read a clock or mint ids) keeps decisions deterministic and
 * repeatable in tests.
 *
 * <p>On {@code start} the current lifecycle and step are empty; on {@code react} both are present.
 * The definition treats this as read-only — it returns a new decision, it does not mutate the
 * context or the state passed alongside it.
 *
 * @param processRef the instance this decision is for
 * @param currentRevision the instance's current optimistic revision
 * @param definitionVersion the version this instance is pinned to
 * @param currentLifecycle the current runtime lifecycle (empty on start)
 * @param currentStep the current business step (empty on start)
 * @param now the runtime-supplied decision time
 * @param cause the context of the input that triggered this decision
 */
public record ProcessContext(
    ProcessRef processRef,
    ProcessRevision currentRevision,
    DefinitionVersion definitionVersion,
    Optional<ProcessLifecycle> currentLifecycle,
    Optional<ProcessStep> currentStep,
    Instant now,
    CommandContext cause) {

  public ProcessContext {
    if (processRef == null) {
      throw new IllegalArgumentException("processRef required");
    }
    if (currentRevision == null) {
      throw new IllegalArgumentException("currentRevision required");
    }
    if (definitionVersion == null) {
      throw new IllegalArgumentException("definitionVersion required");
    }
    if (currentLifecycle == null) {
      throw new IllegalArgumentException(
          "currentLifecycle optional required (use Optional.empty())");
    }
    if (currentStep == null) {
      throw new IllegalArgumentException("currentStep optional required (use Optional.empty())");
    }
    if (now == null) {
      throw new IllegalArgumentException("now required");
    }
    if (cause == null) {
      throw new IllegalArgumentException("cause required");
    }
  }
}
