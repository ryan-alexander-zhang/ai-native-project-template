package com.aipersimmon.ddd.archunit.fixture.good.ordering.process;

import com.aipersimmon.ddd.archunit.fixture.good.ordering.application.GoodConfirmOrder;
import com.aipersimmon.ddd.processmanager.definition.ProcessContext;
import com.aipersimmon.ddd.processmanager.definition.ProcessDecision;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinition;
import com.aipersimmon.ddd.processmanager.definition.ProcessInput;
import com.aipersimmon.ddd.processmanager.effect.DispatchCommand;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A well-formed process definition: it decides from the state, the input and the context it is
 * given, and asks for the command to be sent by <em>returning</em> a {@link DispatchCommand} effect
 * rather than by holding a {@code CommandBus}. Exercises the good path of {@code
 * processDefinitionsShouldBePure}.
 *
 * <p>Three things here are deliberate controls on how strict the rule may be, each of them a shape
 * the framework's own reference definition has:
 *
 * <ul>
 *   <li>It names an application command type — a definition has to say what it wants dispatched, so
 *       the rule cannot be the blunter "depends on nothing outside the process package".
 *   <li>It is a {@code @Component}, because that is how the definition registry finds it.
 *   <li>It takes a {@code @Value}-configured timeout, resolved once at construction. Neither
 *       annotation is a collaborator, which is why the rule exempts annotations from its Spring
 *       check rather than banning the package outright.
 * </ul>
 */
@Component
public class GoodFulfilmentDefinition implements ProcessDefinition<GoodFulfilmentState> {

  private static final ProcessStep AWAITING_CONFIRMATION = new ProcessStep("awaiting-confirmation");

  private final Duration confirmationTimeout;

  public GoodFulfilmentDefinition(
      @Value("${good.fulfilment.confirmation-timeout:PT30S}") Duration confirmationTimeout) {
    this.confirmationTimeout = confirmationTimeout;
  }

  public Duration confirmationTimeout() {
    return confirmationTimeout;
  }

  @Override
  public ProcessType processType() {
    return new ProcessType("good-fulfilment");
  }

  @Override
  public ProcessDecision<GoodFulfilmentState> start(ProcessInput input, ProcessContext context) {
    GoodFulfilmentInput started = (GoodFulfilmentInput) input;
    GoodFulfilmentState state = new GoodFulfilmentState(started.orderId(), AWAITING_CONFIRMATION);
    return ProcessDecision.running(
        state, "started", new DispatchCommand(new GoodConfirmOrder(state.orderId())));
  }

  @Override
  public ProcessDecision<GoodFulfilmentState> react(
      GoodFulfilmentState currentState, ProcessInput input, ProcessContext context) {
    return ProcessDecision.completed(currentState, "confirmed", "FULFILLED");
  }
}
