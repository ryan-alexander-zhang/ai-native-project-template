package com.example.samples.s09.ticketing.application;

import com.aipersimmon.ddd.cqrs.CommandContext;

/**
 * The coordinator's inbound surface, in this context's words: the facts a flow is started by and
 * advanced with.
 *
 * <p>It is a port for one reason worth stating plainly — <strong>nothing above it names the process
 * manager</strong>. The handlers call {@code seatHeld(...)}, not {@code runtime.handle(TYPE, key,
 * new TicketingInput.SeatHeld(...), cause)}. Swapping the coordinator (the library's own javadoc names
 * Temporal as the direction a flow outgrows this engine into) replaces the implementation and leaves
 * every handler here untouched.
 *
 * <p><strong>Why the participants report back at all — orchestration versus choreography.</strong> In
 * choreography each participant publishes a fact and does not know who listens; the flow is an emergent
 * property of everyone's subscriptions, and no single place can answer "where is order 42". In
 * orchestration the coordinator dispatches and the results come back to it, which is what these methods
 * are. The cost is exactly what you see here: a participant's handler knows that a coordinator exists.
 * The benefit is that the flow is one readable object, and that the state of a stuck order is a row you
 * can query rather than a conclusion you have to reconstruct from logs. S9 chose orchestration because
 * the flow has a compensation order — release the seat <em>then</em> cancel, refund <em>before</em>
 * releasing — and an ordering that matters has to live somewhere.
 *
 * <p>Every method takes the causing {@link CommandContext}, so the whole flow — the client's POST, each
 * dispatched effect, each fact coming back — hangs off one correlation id. Nothing about that travels in
 * a payload.
 */
public interface TicketingProcess {

  /** An order exists; start its flow. */
  void orderPlaced(
      String orderId, String customerId, String seatClass, long amountMinor, CommandContext cause);

  void seatHeld(String orderId, CommandContext cause);

  /** No seats left. A business answer, and the flow's shortest compensation path. */
  void seatSoldOut(String orderId, String reason, CommandContext cause);

  /**
   * The money moved, and this is the reference it moved under — the one fact the flow must remember in
   * order to be able to give it back.
   */
  void walletCharged(String orderId, String debitReference, CommandContext cause);

  void walletDeclined(String orderId, String reason, CommandContext cause);

  void walletRefunded(String orderId, CommandContext cause);

  void seatReleased(String orderId, CommandContext cause);

  void ticketIssued(String orderId, CommandContext cause);

  void orderCancelled(String orderId, CommandContext cause);

  /**
   * The customer changed their mind. Unlike everything else here, this arrives from outside the flow, so
   * an instance may not exist at all — an order cancelled before its flow started. The implementation
   * treats that as an ordinary outcome rather than a wiring defect.
   */
  void cancellationRequested(String orderId, String reason, CommandContext cause);
}
