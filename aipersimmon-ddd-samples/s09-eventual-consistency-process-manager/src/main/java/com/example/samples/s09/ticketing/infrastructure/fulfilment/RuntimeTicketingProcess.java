package com.example.samples.s09.ticketing.infrastructure.fulfilment;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.definition.ProcessInput;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.aipersimmon.ddd.processmanager.runtime.ProcessQuery;
import com.aipersimmon.ddd.processmanager.runtime.ProcessRuntime;
import com.example.samples.s09.ticketing.application.TicketingProcess;
import com.example.samples.s09.ticketing.application.fulfilment.TicketingDefinition;
import com.example.samples.s09.ticketing.application.fulfilment.TicketingInput;
import org.springframework.stereotype.Component;

/**
 * The adapter onto the durable runtime — twelve one-line methods, and three things worth knowing.
 *
 * <p><strong>The business key is the order id.</strong> So every fact coming back names the flow it
 * belongs to without anyone threading a saga id through the participants, and one order can have at most
 * one flow (the runtime's duplicate-business-key policy is {@code reject}).
 *
 * <p><strong>Idempotency comes free from the effect's identity.</strong> Each command effect is delivered
 * under the effect's own persisted message id ({@code CommandBus.sendAs}), and the handler passes that
 * same {@code CommandContext} back in here as the cause. So a redelivered effect produces a
 * <em>byte-identical</em> input message id, and the runtime recognises it and returns the original
 * transition instead of advancing twice. That is why this sample needs no inbox: an inbox exists to
 * deduplicate messages arriving from <em>outside</em> the process; these arrive from the coordinator's own
 * effect table, which already has an identity for them. S4's inbox and this are the same idea applied at
 * two different boundaries.
 *
 * <p><strong>Two ways to address an instance, and the difference is a design statement.</strong> Every
 * result fact uses {@code handle(type, key, ...)}, which throws if there is no instance — a fact that can
 * only exist because this flow produced it arriving for no flow is a wiring defect, and a loud one is
 * cheaper than a lost one. A cancellation request instead looks the instance up first, because "the
 * customer cancelled an order whose flow never started" is an ordinary business outcome.
 */
@Component
class RuntimeTicketingProcess implements TicketingProcess {

  private static final ProcessType TYPE = TicketingDefinition.PROCESS_TYPE;

  private final ProcessRuntime runtime;
  private final ProcessQuery query;

  RuntimeTicketingProcess(ProcessRuntime runtime, ProcessQuery query) {
    this.runtime = runtime;
    this.query = query;
  }

  @Override
  public void orderPlaced(
      String orderId, String customerId, String seatClass, long amountMinor, CommandContext cause) {
    runtime.start(
        TYPE,
        new ProcessBusinessKey(orderId),
        new TicketingInput.OrderPlaced(orderId, customerId, seatClass, amountMinor),
        cause);
  }

  @Override
  public void seatHeld(String orderId, CommandContext cause) {
    handle(orderId, new TicketingInput.SeatHeld(orderId), cause);
  }

  @Override
  public void seatSoldOut(String orderId, String reason, CommandContext cause) {
    handle(orderId, new TicketingInput.SeatSoldOut(orderId, reason), cause);
  }

  @Override
  public void walletCharged(String orderId, String debitReference, CommandContext cause) {
    handle(orderId, new TicketingInput.WalletCharged(orderId, debitReference), cause);
  }

  @Override
  public void walletDeclined(String orderId, String reason, CommandContext cause) {
    handle(orderId, new TicketingInput.WalletDeclined(orderId, reason), cause);
  }

  @Override
  public void walletRefunded(String orderId, CommandContext cause) {
    handle(orderId, new TicketingInput.WalletRefunded(orderId), cause);
  }

  @Override
  public void seatReleased(String orderId, CommandContext cause) {
    handle(orderId, new TicketingInput.SeatReleased(orderId), cause);
  }

  @Override
  public void ticketIssued(String orderId, CommandContext cause) {
    handle(orderId, new TicketingInput.TicketIssued(orderId), cause);
  }

  @Override
  public void orderCancelled(String orderId, CommandContext cause) {
    handle(orderId, new TicketingInput.OrderCancelled(orderId), cause);
  }

  @Override
  public void cancellationRequested(String orderId, String reason, CommandContext cause) {
    query
        .findRef(TYPE, new ProcessBusinessKey(orderId))
        .ifPresent(
            ref ->
                runtime.handle(
                    ref, new TicketingInput.CancellationRequested(orderId, reason), cause));
  }

  private void handle(String orderId, ProcessInput input, CommandContext cause) {
    runtime.handle(TYPE, new ProcessBusinessKey(orderId), input, cause);
  }
}
