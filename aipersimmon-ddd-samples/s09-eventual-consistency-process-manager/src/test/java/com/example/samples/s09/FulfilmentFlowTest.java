package com.example.samples.s09;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.tenancy.Tenants;
import com.example.samples.s09.ticketing.application.HoldSeat;
import com.example.samples.s09.ticketing.application.TicketingProcess;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The flow, from every direction it can be pushed: through to a ticket, refused at each step, timed out,
 * and cancelled by the customer at two different moments.
 *
 * <p>Effects are delivered by hand, one round at a time, so each assertion is about a state the flow
 * really passed through rather than about whatever a background worker had got to.
 */
class FulfilmentFlowTest extends FlowTestBase {

  @Autowired private CommandBus commandBus;
  @Autowired private TicketingProcess process;

  @Test
  void thehappyPathTicketsTheOrderAndSpendsTheMoney() {
    String orderId = placeOrder("STALLS", 4500);

    // Nothing has happened yet except the intent: the order row and the flow row, in one transaction.
    assertThat(orderStatus(orderId)).isEqualTo("PLACED");
    assertThat(flowStep(orderId)).isEqualTo("AWAITING_SEAT");
    assertThat(seatsAvailable("STALLS")).isEqualTo(2);

    runToQuiescence();

    assertThat(orderStatus(orderId)).isEqualTo("TICKETED");
    assertThat(flowLifecycle(orderId)).isEqualTo("COMPLETED");
    assertThat(flowOutcome(orderId)).isEqualTo("ticketed");
    assertThat(seatsAvailable("STALLS")).isEqualTo(1);
    assertThat(balance()).isEqualTo(15500);
    assertThat(ledger())
        .extracting("kind", "amount_minor")
        .containsExactly(tuple("DEBIT", 4500L));
  }

  @Test
  void thestepsHappenOneAtATimeAndInOrder() {
    String orderId = placeOrder("STALLS", 4500);

    relay.pollOnce();
    assertThat(flowStep(orderId)).isEqualTo("AWAITING_PAYMENT");
    assertThat(seatsAvailable("STALLS")).as("the seat is held before the money moves").isEqualTo(1);
    assertThat(balance()).isEqualTo(20000);

    relay.pollOnce();
    assertThat(flowStep(orderId)).isEqualTo("AWAITING_TICKET");
    assertThat(balance()).isEqualTo(15500);
    // And here is the window the catalogue asks about: the flow is waiting for the ticket, the order is
    // still PLACED, and neither is wrong. The flow's step is what it is waiting for; the status is what is
    // true of the order. They are not two copies of one fact.
    assertThat(orderStatus(orderId)).isEqualTo("PLACED");

    relay.pollOnce();
    assertThat(orderStatus(orderId)).isEqualTo("TICKETED");
  }

  @Test
  void asoldOutSeatCancelsTheOrderAndNeverTouchesTheMoney() {
    String orderId = placeOrder("BALCONY", 4500);

    runToQuiescence();

    assertThat(orderStatus(orderId)).isEqualTo("CANCELLED");
    assertThat(cancelReason(orderId)).contains("no BALCONY seats left");
    assertThat(flowOutcome(orderId)).isEqualTo("cancelled");
    // The shortest compensation in the flow: nothing had been done, so nothing is undone. The money was
    // never asked for, which is the whole reason the seat is the first step and not the second.
    assertThat(balance()).isEqualTo(20000);
    assertThat(ledger()).isEmpty();
  }

  @Test
  void adeclinedPaymentReleasesTheSeatBeforeCancellingTheOrder() {
    String orderId = placeOrder("STALLS", 999_999);

    relay.pollOnce();
    assertThat(seatsAvailable("STALLS")).isEqualTo(1);

    runToQuiescence();

    assertThat(orderStatus(orderId)).isEqualTo("CANCELLED");
    assertThat(cancelReason(orderId)).contains("insufficient funds");
    assertThat(seatsAvailable("STALLS")).as("the seat came back").isEqualTo(2);
    assertThat(balance()).isEqualTo(20000);
    assertThat(ledger()).as("a refusal moves no money, so there is nothing to make good").isEmpty();
  }

  @Test
  void areleasedSeatKeepsItsHoldRowAndTheTimeItWasLetGo() {
    String orderId = placeOrder("STALLS", 999_999);

    runToQuiescence();

    Map<String, Object> hold =
        jdbc.queryForMap("SELECT * FROM s09_seat_hold WHERE order_id = ?", orderId);
    // The counter is back to where it started and the history is not. A delete would have left the numbers
    // right and the record a lie — this is the smallest form of "a compensation is a new fact, not an
    // erasure".
    assertThat(hold.get("held_at")).isNotNull();
    assertThat(hold.get("released_at")).isNotNull();
  }

  @Test
  void acancellationAfterTheChargeRefundsTheMoneyThenReleasesTheSeat() {
    String orderId = placeOrder("STALLS", 4500);
    relay.pollOnce(); // the seat is held; ChargeWallet is staged and not yet delivered

    // The customer changes their mind while the charge is in flight. The flow records the request and does
    // not act on it: acting now would leave the in-flight charge to land afterwards.
    requestCancellation(orderId, "changed my mind");
    assertThat(flowStep(orderId)).isEqualTo("AWAITING_PAYMENT");
    assertThat(orderStatus(orderId)).isEqualTo("PLACED");

    relay.pollOnce(); // the charge lands — and now there is money to give back
    assertThat(flowStep(orderId)).isEqualTo("AWAITING_REFUND");
    assertThat(balance()).isEqualTo(15500);

    runToQuiescence();

    // Compensation in reverse order: money first, then the seat, then the order.
    assertThat(orderStatus(orderId)).isEqualTo("CANCELLED");
    assertThat(cancelReason(orderId)).contains("changed my mind");
    assertThat(seatsAvailable("STALLS")).isEqualTo(2);
    assertThat(balance()).isEqualTo(20000);
    // The exhibit. The balance is back where it started and the statement has two entries, not zero:
    // a debit and the credit that made it good, the second naming the first.
    assertThat(ledger())
        .extracting("kind", "amount_minor", "reference")
        .containsExactly(
            tuple("DEBIT", 4500L, "ticket-debit:" + orderId),
            tuple("CREDIT", 4500L, "refund-of:ticket-debit:" + orderId));
  }

  @Test
  void acancellationAfterTheTicketIsIssuedIsRefusedByTheFlow() {
    String orderId = placeOrder("STALLS", 4500);
    runToQuiescence();
    assertThat(orderStatus(orderId)).isEqualTo("TICKETED");
    long transitionsBefore = transitionCount(orderId);

    requestCancellation(orderId, "too late");
    runToQuiescence();

    // The point of no return. Undoing a ticket is a refund flow with its own authorisation, not a
    // compensation of this one — so the request changes nothing and the ticket stands.
    assertThat(orderStatus(orderId)).isEqualTo("TICKETED");
    assertThat(flowLifecycle(orderId)).isEqualTo("COMPLETED");
    assertThat(balance()).isEqualTo(15500);
    // And the guarantee is stronger than this flow's own code: the runtime short-circuits a terminal
    // instance before it consults the definition at all (DefaultProcessRuntime:510 returns the last
    // transition as a duplicate), so no transition is written and the definition is never asked. A flow
    // cannot be restarted past its own ending even by a definition that wanted to.
    assertThat(transitionCount(orderId)).isEqualTo(transitionsBefore);
  }

  @Test
  void astepThatIsDeliveredAndNeverAnsweredTimesOutAndCompensates() {
    String orderId = placeOrder("STALLS", 4500);

    // What a deadline is actually for: the command reached the participant and no answer came back. So the
    // effect is marked delivered without anyone handling it, which is the state a lost in-flight answer
    // leaves behind. (The other case — the effect not delivered yet — is bounded by configuration instead:
    // the seat wait must exceed the relay's worst-case delivery time.)
    pretendDeliveredWithoutAnswer("hold-seat");

    assertThat(deadlines.pollOnce()).as("the seat wait is due immediately in this profile").isEqualTo(1);
    assertThat(flowLifecycle(orderId)).isEqualTo("COMPENSATING");

    runToQuiescence();

    assertThat(orderStatus(orderId)).isEqualTo("CANCELLED");
    assertThat(cancelReason(orderId)).contains("timed out");
    assertThat(flowOutcome(orderId)).isEqualTo("cancelled");
    // Nothing was held, and releasing a seat nobody holds is a no-op rather than a failure — which is why
    // the timeout path can compensate for the step it *asked for* instead of guessing what succeeded.
    assertThat(seatsAvailable("STALLS")).isEqualTo(2);
  }

  @Test
  void aparticipantAsksTwiceAndTheSeatIsHeldOnce() {
    String orderId = placeOrder("STALLS", 4500);
    relay.pollOnce();

    // What a redelivered effect looks like from the participant's side: the same command again. The
    // aggregate recognises the order's existing hold and answers ALREADY_HELD.
    commandBus.sendAs(
        new HoldSeat(orderId, "STALLS"), CommandContext.root(Tenants.ROOT, "replay-1"));

    assertThat(seatsAvailable("STALLS")).as("one seat, not two").isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM s09_seat_hold", Long.class)).isEqualTo(1);
  }

  @Test
  void thesameFactDeliveredTwiceAdvancesTheFlowOnce() {
    String orderId = placeOrder("STALLS", 4500);
    CommandContext cause = CommandContext.root(Tenants.ROOT, "seat-held-once");

    process.seatHeld(orderId, cause);
    long transitionsAfterFirst = transitionCount(orderId);
    process.seatHeld(orderId, cause);

    // The second call carries the same input message id, so the runtime returns the original transition
    // instead of advancing again — which is why this sample needs no inbox. An inbox deduplicates messages
    // arriving from outside; these arrive from the coordinator's own effect table, and each effect already
    // has a persisted identity that the participant hands straight back as the cause.
    assertThat(flowStep(orderId)).isEqualTo("AWAITING_PAYMENT");
    assertThat(transitionCount(orderId)).isEqualTo(transitionsAfterFirst);
    assertThat(
            single(
                "SELECT COUNT(*) FROM aipersimmon_process_effect WHERE payload_type LIKE '%charge-wallet%'",
                Long.class))
        .as("one charge staged, not two")
        .isEqualTo(1L);
  }

  @Test
  void anoperatorCanSeeWhereAFlowIsAndStopCoordinatingIt() {
    String orderId = placeOrder("STALLS", 4500);
    relay.pollOnce();

    Map<String, Object> flow = get("/flows/" + orderId).body();
    assertThat(flow).containsEntry("lifecycle", "RUNNING").containsEntry("step", "AWAITING_PAYMENT");

    assertThat(
            post("/flows/" + orderId + "/cancellation", Map.of("operator", "ops", "reason", "stuck"))
                .status())
        .isEqualTo(200);

    assertThat(flowLifecycle(orderId)).isEqualTo("CANCELLED");
    // And the honest cost of the big red button: it stops the coordination, it does not compensate. The
    // seat is still held and the order is still PLACED, which is why the read above exists — an operator
    // has to know what the flow had already done before reaching for this.
    assertThat(seatsAvailable("STALLS")).isEqualTo(1);
    assertThat(orderStatus(orderId)).isEqualTo("PLACED");
  }

  /** Marks a staged effect delivered without anyone having handled it. */
  private void pretendDeliveredWithoutAnswer(String payloadTypeFragment) {
    int updated =
        jdbc.update(
            "UPDATE aipersimmon_process_effect SET status = 'DELIVERED', delivered_at = now()"
                + " WHERE payload_type LIKE ?",
            "%" + payloadTypeFragment + "%");
    assertThat(updated).as("the instrument only works if there was an effect to mark").isEqualTo(1);
  }
}
