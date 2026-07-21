package com.aipersimmon.ddd.processmanager.runtime;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.definition.ProcessInput;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessRef;
import com.aipersimmon.ddd.processmanager.model.ProcessType;

/**
 * The command-side port that advances a durable process: starting a new instance or feeding an
 * input to an existing one. Each call resolves the definition, runs the pure decision, and
 * atomically persists the new snapshot, the transition, and the staged effects — all in one local
 * transaction; effects are delivered afterwards by a relay, never inside this transaction.
 *
 * <p>There is deliberately no {@code setState} or {@code forceStep}: state changes only through a
 * definition's decision. Operator redrive re-dispatches existing effects or re-submits a recorded
 * input; it never edits state arbitrarily.
 *
 * <p>Idempotency: {@code start} with the same {@code (processType, businessKey, inputMessageId)}
 * returns the original transition's duplicate result; the same {@code (processType, businessKey)}
 * with a different input message id is governed by the configured duplicate-business-key policy
 * (reject or fold). {@code handle} addresses an instance by its full {@link ProcessRef}.
 */
public interface ProcessRuntime {

  /**
   * Start a new instance of {@code processType} for {@code businessKey}.
   *
   * @param processType the logical process type
   * @param businessKey the business correlation key
   * @param input the starting input
   * @param cause the context of the message that triggered the start
   * @return the advance result (new ref, revision, lifecycle, step, duplicate flag)
   */
  ProcessAdvanceResult start(
      ProcessType processType,
      ProcessBusinessKey businessKey,
      ProcessInput input,
      CommandContext cause);

  /**
   * Advance an existing instance with an input.
   *
   * @param processRef the full reference of the target instance
   * @param input the input to react to
   * @param cause the context of the message that carried the input
   * @return the advance result
   */
  ProcessAdvanceResult handle(ProcessRef processRef, ProcessInput input, CommandContext cause);
}
