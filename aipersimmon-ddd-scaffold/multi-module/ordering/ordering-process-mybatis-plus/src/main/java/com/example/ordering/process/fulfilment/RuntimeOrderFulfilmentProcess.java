package com.example.ordering.process.fulfilment;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.definition.ProcessInput;
import com.aipersimmon.ddd.processmanager.engine.runtime.DefaultProcessQuery;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessRef;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.aipersimmon.ddd.processmanager.runtime.ProcessRuntime;
import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.TenantId;
import com.aipersimmon.ddd.tenancy.Tenants;
import com.example.ordering.application.fulfilment.OrderFulfilmentProcess;
import org.springframework.stereotype.Component;

/**
 * Drives the order-fulfilment {@link OrderFulfilmentProcess} through the durable {@link
 * ProcessRuntime}: {@code readyForFulfilment} starts the instance; each subsequent fact resolves
 * the instance's {@link ProcessRef} from its order id (the business key) and hands the input to
 * {@code handle}. The runtime stages the ordering commands as effects and a relay delivers them —
 * so the coordination is durable and at-least-once, not a synchronous in-memory saga.
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
  private final DefaultProcessQuery query;

  public RuntimeOrderFulfilmentProcess(ProcessRuntime runtime, DefaultProcessQuery query) {
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

  @Override
  public void orderCancelled(String orderId) {
    handle(
        orderId,
        new OrderFulfilmentInput.OrderCancelled(orderId),
        factContext("cancelled", orderId));
  }

  private void handle(String orderId, ProcessInput input, CommandContext cause) {
    ProcessRef ref =
        query
            .findRef(TYPE, new ProcessBusinessKey(orderId))
            .orElseThrow(
                () ->
                    new IllegalStateException("no order-fulfilment instance for order " + orderId));
    runtime.handle(ref, input, cause);
  }

  /**
   * A fresh context for a domain fact that arrives without an inbound message, keyed by the order
   * id but stamped with the ambient tenant so the process instance is created — and every
   * tenant-scoped advance thereafter is found — under the tenant whose command raised the fact.
   * Falls to the {@code __root__} sentinel only when no tenant is bound (single-tenant N=1).
   */
  private static CommandContext factContext(String fact, String orderId) {
    String tenant = TenantContext.current().map(TenantId::value).orElse(Tenants.ROOT.value());
    return CommandContext.root(tenant, fact + ":" + orderId);
  }
}
