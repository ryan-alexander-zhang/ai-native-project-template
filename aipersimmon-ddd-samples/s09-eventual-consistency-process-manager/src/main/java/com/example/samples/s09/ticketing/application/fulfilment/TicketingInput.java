package com.example.samples.s09.ticketing.application.fulfilment;

import com.aipersimmon.ddd.processmanager.definition.ProcessInput;

/**
 * Everything the flow can be told, as one sealed set.
 *
 * <p>Sealed for readability rather than for exhaustiveness, and the distinction is worth being precise
 * about: the definition's per-input switches carry a {@code default -> ignored(...)} arm, so they are
 * <em>not</em> compiler-checked for completeness. That is deliberate — a thirteenth input should default
 * to being absorbed at every step it has no meaning for, not fail to compile in six places. What the
 * compiler does check is the switch over the flow's {@code Step}, which has no default: a new step cannot
 * be added without deciding how it reacts.
 *
 * <p>Three kinds live here, and telling them apart is worth doing when reading the definition:
 *
 * <ul>
 *   <li><strong>Results of what the flow asked for</strong> — {@code SeatHeld}, {@code SeatSoldOut},
 *       {@code WalletCharged}, {@code WalletDeclined}, {@code WalletRefunded}, {@code SeatReleased},
 *       {@code TicketIssued}, {@code OrderCancelled}. Each is the answer to one dispatched command.
 *   <li><strong>The flow's own timers</strong> — {@code SeatWaitTimedOut}, {@code PaymentWaitTimedOut}.
 *       A fired deadline comes back through the same {@code handle} as everything else, which is what
 *       makes a timeout an ordinary row in the transition table instead of a callback with its own rules.
 *   <li><strong>A request from outside</strong> — {@code CancellationRequested}. The only one that can
 *       arrive at any step, including steps where the right answer is to remember it and carry on.
 * </ul>
 *
 * <p>Each carries business fields only; correlation travels in the {@code CommandContext}.
 */
public sealed interface TicketingInput extends ProcessInput {

  String orderId();

  /** The order exists: start coordinating it. */
  record OrderPlaced(String orderId, String customerId, String seatClass, long amountMinor)
      implements TicketingInput {}

  record SeatHeld(String orderId) implements TicketingInput {}

  record SeatSoldOut(String orderId, String reason) implements TicketingInput {}

  /** The seat step said nothing in time. */
  record SeatWaitTimedOut(String orderId) implements TicketingInput {}

  /** Carries the reference the money moved under — the flow's only way to give it back later. */
  record WalletCharged(String orderId, String debitReference) implements TicketingInput {}

  record WalletDeclined(String orderId, String reason) implements TicketingInput {}

  /** The payment step said nothing in time. Treated exactly like a decline: no money moved. */
  record PaymentWaitTimedOut(String orderId) implements TicketingInput {}

  record WalletRefunded(String orderId) implements TicketingInput {}

  record SeatReleased(String orderId) implements TicketingInput {}

  record TicketIssued(String orderId) implements TicketingInput {}

  record OrderCancelled(String orderId) implements TicketingInput {}

  /** The customer changed their mind, at whatever step the flow happens to be at. */
  record CancellationRequested(String orderId, String reason) implements TicketingInput {}
}
