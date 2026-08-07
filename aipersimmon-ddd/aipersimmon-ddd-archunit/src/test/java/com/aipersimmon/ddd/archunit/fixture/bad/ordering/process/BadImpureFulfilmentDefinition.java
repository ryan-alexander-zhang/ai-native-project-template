package com.aipersimmon.ddd.archunit.fixture.bad.ordering.process;

import com.aipersimmon.ddd.archunit.fixture.good.ordering.application.GoodConfirmOrder;
import com.aipersimmon.ddd.archunit.fixture.good.ordering.process.GoodFulfilmentState;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.processmanager.definition.ProcessContext;
import com.aipersimmon.ddd.processmanager.definition.ProcessDecision;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinition;
import com.aipersimmon.ddd.processmanager.definition.ProcessInput;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import java.time.Instant;

/**
 * Violates both halves of {@code processDefinitionsShouldBePure}: it holds a {@link CommandBus} and
 * sends through it from inside the decision, and it branches on {@link Instant#now()}.
 *
 * <p>The two failures are the two ways a durable process quietly stops being durable. The direct
 * send happens again on every redelivery and recovery — uncounted by the effect ledger, outside the
 * transaction that persists the transition — where the returned {@code DispatchCommand} effect
 * would have been dispatched once. The clock read makes the replay of a stored transition reach a
 * different conclusion than the original run.
 */
public class BadImpureFulfilmentDefinition implements ProcessDefinition<GoodFulfilmentState> {

  private static final ProcessStep AWAITING_CONFIRMATION = new ProcessStep("awaiting-confirmation");

  private final CommandBus commandBus;

  public BadImpureFulfilmentDefinition(CommandBus commandBus) {
    this.commandBus = commandBus;
  }

  @Override
  public ProcessType processType() {
    return new ProcessType("bad-fulfilment");
  }

  @Override
  public ProcessDecision<GoodFulfilmentState> start(ProcessInput input, ProcessContext context) {
    commandBus.send(new GoodConfirmOrder("order-1"));
    return ProcessDecision.running(
        new GoodFulfilmentState("order-1", AWAITING_CONFIRMATION), "started");
  }

  @Override
  public ProcessDecision<GoodFulfilmentState> react(
      GoodFulfilmentState currentState, ProcessInput input, ProcessContext context) {
    if (Instant.now().isAfter(Instant.EPOCH)) {
      return ProcessDecision.completed(currentState, "confirmed", "FULFILLED");
    }
    return ProcessDecision.running(currentState, "waiting");
  }
}
