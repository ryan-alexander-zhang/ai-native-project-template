package com.example.ordering.process.fulfilment;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.definition.ProcessInput;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.aipersimmon.ddd.processmanager.runtime.ProcessQuery;
import com.aipersimmon.ddd.processmanager.runtime.ProcessRuntime;
import com.aipersimmon.ddd.tenancy.TenantContext;
import com.example.ordering.application.fulfilment.OrderFulfilmentProcess;
import org.springframework.stereotype.Component;

/**
 * Drives the order-fulfilment {@link OrderFulfilmentProcess} through the durable {@link
 * ProcessRuntime}: {@code readyForFulfilment} starts the instance; each subsequent fact hands its
 * input to {@code handle} addressed by the order id (the business key) — the runtime resolves the
 * instance under the advancing tenant. The runtime stages the ordering commands as effects and a
 * relay delivers them — so the coordination is durable and at-least-once, not a synchronous
 * in-memory saga.
 *
 * <p>The domain facts (ready-for-fulfilment, confirmed, cancelled) arrive without an inbound
 * message, so they mint a fresh context keyed by the order id but stamped with the ambient tenant
 * ({@link TenantContext}, bound by the command that raised the fact) — so the instance is created,
 * and every later advance found, under the right tenant. The cross-context result facts instead
 * carry the triggering event's context, keeping the causal chain intact.
 */
@Component
public class RuntimeOrderFulfilmentProcess implements OrderFulfilmentProcess {

  private static final ProcessType TYPE = OrderFulfilmentDefinition.PROCESS_TYPE;

  private final ProcessRuntime runtime;
  private final ProcessQuery query;

  /**
   * Both collaborators are the framework <em>ports</em>: swapping the process-manager provider (the
   * javadoc on these ports names Temporal/Seata) replaces the beans behind them, and this class
   * does not change. It used to import the engine's {@code DefaultProcessQuery} for the
   * by-business-key lookup, which broke exactly that promise (issue-00136).
   */
  public RuntimeOrderFulfilmentProcess(ProcessRuntime runtime, ProcessQuery query) {
    this.runtime = runtime;
    this.query = query;
  }

  @Override
  public void readyForFulfilment(String orderId) {
    runtime.start(
        TYPE,
        new ProcessBusinessKey(orderId),
        new OrderFulfilmentInput.ReadyForFulfilment(orderId),
        factContext("ready-for-fulfilment", orderId));
  }

  @Override
  public void stockReserved(String orderId, String reservationId, CommandContext cause) {
    handle(orderId, new OrderFulfilmentInput.StockReserved(orderId, reservationId), cause);
  }

  @Override
  public void stockReservationFailed(
      String orderId, String code, String reason, CommandContext cause) {
    handle(orderId, new OrderFulfilmentInput.StockReservationFailed(orderId, code, reason), cause);
  }

  @Override
  public void paymentAuthorized(String orderId, CommandContext cause) {
    handle(orderId, new OrderFulfilmentInput.PaymentAuthorized(orderId), cause);
  }

  @Override
  public void paymentDeclined(String orderId, String code, String reason, CommandContext cause) {
    handle(orderId, new OrderFulfilmentInput.PaymentDeclined(orderId, code, reason), cause);
  }

  @Override
  public void stockReleased(String orderId, String reservationId, CommandContext cause) {
    handle(orderId, new OrderFulfilmentInput.StockReleased(orderId, reservationId), cause);
  }

  @Override
  public void orderConfirmed(String orderId) {
    handle(
        orderId,
        new OrderFulfilmentInput.OrderConfirmed(orderId),
        factContext("confirmed", orderId));
  }

  /**
   * Unlike the other two facts, this one can legitimately arrive for an order that has no flow at
   * all: a customer cancelling before fulfilment starts, or a review rejection. Those orders never
   * reached {@code READY_FOR_FULFILMENT}, so no instance was ever started, and there is nothing to
   * advance — the cancellation is the whole story. Demanding an instance here would turn an
   * ordinary business outcome into a 500.
   */
  @Override
  public void orderCancelled(String orderId) {
    query
        .findRef(TYPE, new ProcessBusinessKey(orderId))
        .ifPresent(
            ref ->
                runtime.handle(
                    ref,
                    new OrderFulfilmentInput.OrderCancelled(orderId),
                    factContext("cancelled", orderId)));
  }

  /**
   * For a fact that can only exist because a flow produced it. A missing instance is then a wiring
   * defect, not a business case, and the runtime's by-business-key {@code handle} fails loudly on
   * it ({@code ProcessNotFoundException}) — which is the point. Contrast {@link #orderCancelled},
   * where "no instance" is an ordinary business outcome and so checks {@code findRef} first.
   */
  private void handle(String orderId, ProcessInput input, CommandContext cause) {
    runtime.handle(TYPE, new ProcessBusinessKey(orderId), input, cause);
  }

  /**
   * A fresh context for a domain fact that arrives without an inbound message, keyed by the order
   * id but stamped with the ambient tenant so the process instance is created — and every
   * tenant-scoped advance thereafter is found — under the tenant whose command raised the fact.
   *
   * <p>{@code effective()}, not {@code current().orElse(ROOT)}: what an unbound thread means is a
   * deployment-wide decision {@code TenantContext} already makes from the tenancy mode. Re-deciding
   * it here would be worse than one mislabelled row — the tenant stamped now is the one every later
   * advance looks the instance up under, so a flow started under the sentinel is a flow the real
   * tenant can never advance again.
   */
  private static CommandContext factContext(String fact, String orderId) {
    return CommandContext.root(TenantContext.effective(), fact + ":" + orderId);
  }
}
