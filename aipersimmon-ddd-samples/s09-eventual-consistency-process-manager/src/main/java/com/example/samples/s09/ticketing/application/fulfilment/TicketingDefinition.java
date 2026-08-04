package com.example.samples.s09.ticketing.application.fulfilment;

import static com.aipersimmon.ddd.processmanager.definition.ProcessDecision.compensating;
import static com.aipersimmon.ddd.processmanager.definition.ProcessDecision.completed;
import static com.aipersimmon.ddd.processmanager.definition.ProcessDecision.ignored;
import static com.aipersimmon.ddd.processmanager.definition.ProcessDecision.running;

import com.aipersimmon.ddd.processmanager.definition.ProcessContext;
import com.aipersimmon.ddd.processmanager.definition.ProcessDecision;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinition;
import com.aipersimmon.ddd.processmanager.definition.ProcessInput;
import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.processmanager.effect.CancelDeadline;
import com.aipersimmon.ddd.processmanager.effect.DispatchCommand;
import com.aipersimmon.ddd.processmanager.effect.ProcessEffect;
import com.aipersimmon.ddd.processmanager.effect.ScheduleDeadline;
import com.aipersimmon.ddd.processmanager.exception.UnsupportedProcessInputException;
import com.aipersimmon.ddd.processmanager.model.DeadlineName;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.example.samples.s09.ticketing.application.CancelTicketOrder;
import com.example.samples.s09.ticketing.application.ChargeWallet;
import com.example.samples.s09.ticketing.application.HoldSeat;
import com.example.samples.s09.ticketing.application.IssueTicket;
import com.example.samples.s09.ticketing.application.RefundWallet;
import com.example.samples.s09.ticketing.application.ReleaseSeat;
import com.example.samples.s09.ticketing.application.fulfilment.TicketingState.Step;
import java.time.Duration;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The flow, as one object you can read top to bottom.
 *
 * <p>That readability is the reason to orchestrate rather than choreograph, and it is worth naming what
 * makes it possible: <strong>this class is pure</strong>. No repository, no bus, no clock, no HTTP, no
 * Spring bean beyond two configured durations. It receives the current state, one input and a read-only
 * context, and returns the next state plus the effects to perform after the transition commits. So it can
 * be unit-tested with no database and replayed without consequence — and it also cannot cheat: anything a
 * later step needs must be in the state, because there is nothing to look it up from.
 *
 * <p><strong>The forward path</strong> is three steps, each waiting for one answer:
 * seat → money → ticket. <strong>The compensating path</strong> is the same steps in reverse, and it is
 * not a rollback in any of them:
 *
 * <table>
 *   <caption>compensation, step by step</caption>
 *   <tr><th>what was done</th><th>what makes it good</th><th>why it is not an undo</th></tr>
 *   <tr><td>the wallet was debited</td><td>a credit, referencing the debit</td>
 *       <td>both entries stay on the statement forever</td></tr>
 *   <tr><td>a seat was held</td><td>the hold is marked released</td>
 *       <td>the row is kept, with the time it was let go</td></tr>
 *   <tr><td>the order was placed</td><td>the order is cancelled, with a reason</td>
 *       <td>a cancelled order is a business fact, not an absence</td></tr>
 * </table>
 *
 * <p><strong>And there is a point of no return.</strong> Once the ticket is issued the flow is over: a
 * cancellation arriving afterwards is {@link ProcessDecision#ignored ignored} here, because undoing a
 * ticket is a refund flow with its own authorisation and its own rules, not a compensation of this one.
 * The aggregate agrees loudly — {@code TicketOrder.cancel} throws after ticketing — so a flow that tried
 * would poison its own effect relay rather than quietly cancel something a customer is holding.
 *
 * <p><strong>Every step absorbs what it cannot use.</strong> The runtime is at-least-once, so duplicates
 * and late timers arrive as a matter of course; {@code ignored()} is the arm that makes them no-ops
 * instead of wrong transitions — or, worse, exceptions, which an at-least-once runtime reads as a poison
 * message and retries.
 */
@Component
public class TicketingDefinition implements ProcessDefinition<TicketingState> {

  /** The logical type. A string, because it outlives this class's name. */
  public static final ProcessType PROCESS_TYPE = new ProcessType("ticketing.fulfilment");

  static final DeadlineName SEAT_WAIT = new DeadlineName("seat-wait");
  static final DeadlineName PAYMENT_WAIT = new DeadlineName("payment-wait");

  private final Duration seatWait;
  private final Duration paymentWait;

  public TicketingDefinition(
      @Value("${ticketing.seat-wait:PT30S}") Duration seatWait,
      @Value("${ticketing.payment-wait:PT60S}") Duration paymentWait) {
    this.seatWait = seatWait;
    this.paymentWait = paymentWait;
  }

  @Override
  public ProcessType processType() {
    return PROCESS_TYPE;
  }

  /**
   * Every class this flow can receive as an input or stage as an effect.
   *
   * <p>Declaring it is opt-in and worth the twenty lines: the startup validator reconciles this set
   * against the codec registry and refuses to boot naming whatever is missing. Without it, a forgotten
   * codec registration surfaces as an encode failure inside somebody's transaction, on the first order
   * that happens to reach that step — which in a compensating branch might be a week after the deploy.
   */
  @Override
  public Set<Class<?>> declaredPayloads() {
    return Set.of(
        TicketingInput.OrderPlaced.class,
        TicketingInput.SeatHeld.class,
        TicketingInput.SeatSoldOut.class,
        TicketingInput.SeatWaitTimedOut.class,
        TicketingInput.WalletCharged.class,
        TicketingInput.WalletDeclined.class,
        TicketingInput.PaymentWaitTimedOut.class,
        TicketingInput.WalletRefunded.class,
        TicketingInput.SeatReleased.class,
        TicketingInput.TicketIssued.class,
        TicketingInput.OrderCancelled.class,
        TicketingInput.CancellationRequested.class,
        HoldSeat.class,
        ReleaseSeat.class,
        ChargeWallet.class,
        RefundWallet.class,
        IssueTicket.class,
        CancelTicketOrder.class);
  }

  @Override
  public ProcessDecision<TicketingState> start(ProcessInput input, ProcessContext context) {
    if (!(input instanceof TicketingInput.OrderPlaced placed)) {
      throw new UnsupportedProcessInputException(
          "a ticketing flow starts on OrderPlaced, not " + input.getClass().getSimpleName());
    }
    // Two effects, and the second is what makes the first survivable: if the seat step never answers,
    // the timer comes back as an input and the flow compensates instead of waiting forever.
    return running(
        TicketingState.started(placed),
        "seat-requested",
        new DispatchCommand(new HoldSeat(placed.orderId(), placed.seatClass())),
        new ScheduleDeadline(
            SEAT_WAIT,
            context.now().plus(seatWait),
            new TicketingInput.SeatWaitTimedOut(placed.orderId())));
  }

  @Override
  public ProcessDecision<TicketingState> react(
      TicketingState state, ProcessInput input, ProcessContext context) {
    if (!(input instanceof TicketingInput fact)) {
      throw new UnsupportedProcessInputException(
          "not a ticketing input: " + input.getClass().getName());
    }
    return switch (state.step()) {
      case AWAITING_SEAT -> onAwaitingSeat(state, fact, context);
      case AWAITING_PAYMENT -> onAwaitingPayment(state, fact, context);
      case AWAITING_TICKET -> onAwaitingTicket(state, fact, context);
      case AWAITING_REFUND -> onAwaitingRefund(state, fact, context);
      case AWAITING_SEAT_RELEASE -> onAwaitingSeatRelease(state, fact, context);
      case AWAITING_CANCELLATION -> onAwaitingCancellation(state, fact, context);
      // Terminal. A flow that has finished still receives things — a duplicate fact, a timer that was
      // cancelled a millisecond too late — and absorbing them is the whole job here.
      case TICKETED, CANCELLED -> ignored(context, state, fact);
    };
  }

  private ProcessDecision<TicketingState> onAwaitingSeat(
      TicketingState state, TicketingInput fact, ProcessContext context) {
    return switch (fact) {
      case TicketingInput.SeatHeld held ->
          state.cancellationRequested()
              // The customer asked to stop while this was in flight. Now that the seat exists, there is
              // something to give back — so the compensating branch starts here rather than when the
              // request arrived.
              ? compensating(
                  state.seatHeld(Step.AWAITING_SEAT_RELEASE),
                  "seat-held-but-cancelled",
                  new CancelDeadline(SEAT_WAIT),
                  new DispatchCommand(new ReleaseSeat(state.orderId(), state.seatClass())))
              : running(
                  state.seatHeld(Step.AWAITING_PAYMENT),
                  "payment-requested",
                  new CancelDeadline(SEAT_WAIT),
                  new DispatchCommand(
                      new ChargeWallet(
                          state.orderId(), state.customerId(), state.amountMinor())),
                  new ScheduleDeadline(
                      PAYMENT_WAIT,
                      context.now().plus(paymentWait),
                      new TicketingInput.PaymentWaitTimedOut(state.orderId())));

      // Nothing was held, so there is nothing to release: the shortest compensation path in the flow.
      case TicketingInput.SeatSoldOut soldOut ->
          cancelOrder(state, soldOut.reason(), "sold-out", new CancelDeadline(SEAT_WAIT));

      // Silence. Unlike the refusal above, this does NOT mean no seat was taken — it means we do not
      // know, which is a different thing and the reason this branch releases rather than going straight
      // to cancellation. Releasing is safe either way: the handler reports success whether or not there
      // was a hold. Compensate for the step you asked for, not for the step you believe succeeded.
      //
      // The residual race is worth naming: if the HoldSeat effect has not even been delivered yet, the
      // release will find nothing and the hold may land afterwards. That is bounded by configuration
      // rather than by logic — the seat-wait must exceed the relay's worst-case delivery time, the same
      // floor S7's reconciler needed for the same reason.
      case TicketingInput.SeatWaitTimedOut ignoredTimer ->
          releaseSeat(state, "the seat request timed out", "seat-timed-out");

      // Remembered, not acted on: the HoldSeat effect may already be on its way, and cancelling now
      // would leave a seat held for an order nobody is coordinating.
      case TicketingInput.CancellationRequested requested ->
          running(state.cancellationRequested(requested.reason()), "cancellation-noted");

      default -> ignored(context, state, fact);
    };
  }

  private ProcessDecision<TicketingState> onAwaitingPayment(
      TicketingState state, TicketingInput fact, ProcessContext context) {
    return switch (fact) {
      case TicketingInput.WalletCharged charged ->
          state.cancellationRequested()
              // Money moved for an order the customer no longer wants: compensate in reverse order,
              // money first.
              ? compensating(
                  state.charged(charged.debitReference(), Step.AWAITING_REFUND),
                  "charged-but-cancelled",
                  new CancelDeadline(PAYMENT_WAIT),
                  refund(state.charged(charged.debitReference(), Step.AWAITING_REFUND)))
              : running(
                  state.charged(charged.debitReference(), Step.AWAITING_TICKET),
                  "ticket-requested",
                  new CancelDeadline(PAYMENT_WAIT),
                  new DispatchCommand(new IssueTicket(state.orderId())));

      case TicketingInput.WalletDeclined declined ->
          releaseSeat(state, declined.reason(), "payment-declined", new CancelDeadline(PAYMENT_WAIT));

      // No money moved, so this is a decline in everything but name.
      case TicketingInput.PaymentWaitTimedOut ignoredTimer ->
          releaseSeat(state, "the payment timed out", "payment-timed-out");

      case TicketingInput.CancellationRequested requested ->
          running(state.cancellationRequested(requested.reason()), "cancellation-noted");

      default -> ignored(context, state, fact);
    };
  }

  private ProcessDecision<TicketingState> onAwaitingTicket(
      TicketingState state, TicketingInput fact, ProcessContext context) {
    return switch (fact) {
      case TicketingInput.TicketIssued issued ->
          completed(state.at(Step.TICKETED), "ticketed", "ticketed");

      // The point of no return, and the one place a cancellation request is refused rather than deferred.
      // The IssueTicket effect is already in flight; the order is about to be, or already is, a ticket
      // somebody holds. Giving it back is a refund flow with its own authorisation — a new process, not
      // a compensation of this one.
      case TicketingInput.CancellationRequested tooLate -> ignored(context, state, fact);

      default -> ignored(context, state, fact);
    };
  }

  private ProcessDecision<TicketingState> onAwaitingRefund(
      TicketingState state, TicketingInput fact, ProcessContext context) {
    return switch (fact) {
      case TicketingInput.WalletRefunded refunded ->
          state.seatHeld()
              ? compensating(
                  state.at(Step.AWAITING_SEAT_RELEASE),
                  "refunded",
                  new DispatchCommand(new ReleaseSeat(state.orderId(), state.seatClass())))
              : compensating(
                  state.at(Step.AWAITING_CANCELLATION),
                  "refunded",
                  new DispatchCommand(
                      new CancelTicketOrder(state.orderId(), reasonOf(state))));

      default -> ignored(context, state, fact);
    };
  }

  private ProcessDecision<TicketingState> onAwaitingSeatRelease(
      TicketingState state, TicketingInput fact, ProcessContext context) {
    return switch (fact) {
      case TicketingInput.SeatReleased released ->
          compensating(
              state.at(Step.AWAITING_CANCELLATION),
              "seat-released",
              new DispatchCommand(new CancelTicketOrder(state.orderId(), reasonOf(state))));

      default -> ignored(context, state, fact);
    };
  }

  private ProcessDecision<TicketingState> onAwaitingCancellation(
      TicketingState state, TicketingInput fact, ProcessContext context) {
    return switch (fact) {
      case TicketingInput.OrderCancelled cancelled ->
          completed(state.at(Step.CANCELLED), "cancelled", "cancelled");

      default -> ignored(context, state, fact);
    };
  }

  /** Compensate by releasing the seat first; the cancellation follows when the release comes back. */
  private ProcessDecision<TicketingState> releaseSeat(
      TicketingState state, String why, String code, ProcessEffect... extra) {
    TicketingState next = state.compensatingBecause(why, Step.AWAITING_SEAT_RELEASE);
    return compensating(
        next, code, then(extra, new ReleaseSeat(state.orderId(), state.seatClass())));
  }

  /** Compensate straight to the end: nothing was done that needs undoing. */
  private ProcessDecision<TicketingState> cancelOrder(
      TicketingState state, String why, String code, ProcessEffect... extra) {
    TicketingState next = state.compensatingBecause(why, Step.AWAITING_CANCELLATION);
    return compensating(next, code, then(extra, new CancelTicketOrder(state.orderId(), why)));
  }

  private DispatchCommand refund(TicketingState state) {
    return new DispatchCommand(
        new RefundWallet(
            state.orderId(),
            state.customerId(),
            state.amountMinor(),
            state.debitReference(),
            reasonOf(state)));
  }

  private static String reasonOf(TicketingState state) {
    return state.reason() == null ? "the fulfilment flow could not complete" : state.reason();
  }

  /**
   * The deadline-cancelling effects first, then the command.
   *
   * <p>Order is preserved by {@code ProcessDecision} and honoured by the relay, which matters: cancelling
   * a timer before dispatching the work means a redelivery of the batch cannot re-arm a timer for a step
   * that has already moved on.
   */
  private static ProcessEffect[] then(ProcessEffect[] before, Command<?> command) {
    ProcessEffect[] all = new ProcessEffect[before.length + 1];
    System.arraycopy(before, 0, all, 0, before.length);
    all[before.length] = new DispatchCommand(command);
    return all;
  }
}
