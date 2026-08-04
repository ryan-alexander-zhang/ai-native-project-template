package com.example.samples.s09.ticketing.application.fulfilment;

import com.aipersimmon.ddd.processmanager.definition.HasStep;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;

/**
 * What one flow remembers — and the answer to the catalogue's hardest question about process managers.
 *
 * <p><strong>There is no {@code OrderStatus} here, and there never may be.</strong> The order's status is
 * a conclusion the {@code TicketOrder} aggregate owns; copying it into this record would create two
 * answers to one question, and the copy would be the stale one within milliseconds. A test asserts
 * structurally that this record has no component of that type. The rule that generalises: <em>a flow may
 * remember facts, never conclusions.</em>
 *
 * <p>So why does it hold {@code customerId}, {@code seatClass} and {@code amountMinor}, which are also
 * on the order? Because a {@link com.aipersimmon.ddd.processmanager.definition.ProcessDefinition} does no
 * I/O — it cannot read the order — and every compensating command needs them: releasing a seat needs the
 * class, refunding needs the customer and the amount. They are immutable facts of this flow's instance,
 * fixed when it started; they cannot drift because nothing can change them. That is the whole test to
 * apply: could this value change independently after the flow copied it? If yes, do not copy it.
 *
 * <p>{@code seatHeld} and {@code debitReference} are the flow's compensation memory — what has actually
 * been done that would have to be undone. {@code debitReference} in particular is the only place the
 * refund's target is written down.
 *
 * <p>{@code cancellationRequested} exists because a request from outside can arrive while a command is
 * still in flight. Acting on it immediately would leave the in-flight step's effect to land afterwards —
 * a seat held for an order nobody is coordinating any more. So the flow records the intent and takes the
 * compensating branch at the next input it receives, which is the only point at which it knows what there
 * is to undo.
 *
 * @param step the business step, which the runtime also persists as its own column — {@link HasStep} is
 *     what keeps the two from drifting
 */
public record TicketingState(
    String orderId,
    String customerId,
    String seatClass,
    long amountMinor,
    Step step,
    boolean seatHeld,
    String debitReference,
    boolean cancellationRequested,
    String reason)
    implements HasStep {

  /**
   * The steps, forward and compensating. Named for <em>what the flow is waiting for</em>, never for what
   * is true of the order — that is the aggregate's vocabulary, and mixing the two is how the copy this
   * class refuses to hold creeps in through the back door.
   */
  public enum Step {
    AWAITING_SEAT,
    AWAITING_PAYMENT,
    AWAITING_TICKET,

    /** Compensating, in reverse order of what was done: money first. */
    AWAITING_REFUND,
    AWAITING_SEAT_RELEASE,
    AWAITING_CANCELLATION,

    TICKETED,
    CANCELLED
  }

  /**
   * {@link HasStep}: the decision factories read the step from here, so each decision names it once — in
   * the state — instead of twice. The library's {@code ProcessDecision} constructor then verifies the two
   * agree, which is a guard worth having because the step is a column and the state is an encoded blob,
   * and nothing downstream could reconcile them after they diverged.
   */
  @Override
  public ProcessStep processStep() {
    return new ProcessStep(step.name());
  }

  static TicketingState started(TicketingInput.OrderPlaced placed) {
    return new TicketingState(
        placed.orderId(),
        placed.customerId(),
        placed.seatClass(),
        placed.amountMinor(),
        Step.AWAITING_SEAT,
        false,
        null,
        false,
        null);
  }

  TicketingState at(Step next) {
    return new TicketingState(
        orderId,
        customerId,
        seatClass,
        amountMinor,
        next,
        seatHeld,
        debitReference,
        cancellationRequested,
        reason);
  }

  TicketingState seatHeld(Step next) {
    return new TicketingState(
        orderId,
        customerId,
        seatClass,
        amountMinor,
        next,
        true,
        debitReference,
        cancellationRequested,
        reason);
  }

  TicketingState charged(String debitReference, Step next) {
    return new TicketingState(
        orderId,
        customerId,
        seatClass,
        amountMinor,
        next,
        seatHeld,
        debitReference,
        cancellationRequested,
        reason);
  }

  TicketingState compensatingBecause(String why, Step next) {
    return new TicketingState(
        orderId,
        customerId,
        seatClass,
        amountMinor,
        next,
        seatHeld,
        debitReference,
        cancellationRequested,
        why);
  }

  /** Remember that the customer asked to stop, without acting on it yet. */
  TicketingState cancellationRequested(String why) {
    return new TicketingState(
        orderId,
        customerId,
        seatClass,
        amountMinor,
        step,
        seatHeld,
        debitReference,
        true,
        why);
  }
}
