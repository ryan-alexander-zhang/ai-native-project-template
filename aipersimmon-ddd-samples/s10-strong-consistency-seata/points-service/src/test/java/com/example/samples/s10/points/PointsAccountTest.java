package com.example.samples.s10.points;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.samples.s10.points.domain.AwardOutcome;
import com.example.samples.s10.points.domain.PointsAccount;
import com.example.samples.s10.points.domain.PointsAccountId;
import com.example.samples.s10.points.domain.ReserveOutcome;
import com.example.samples.s10.points.domain.SettleOutcome;
import org.junit.jupiter.api.Test;

/**
 * The participant's rules, with no database, no Spring and no Seata.
 *
 * <p>Worth doing at this layer because every one of these properties is a property of the <em>model</em>
 * rather than of the protocol. Seata retries Confirm and Cancel; whether retrying is safe is decided here, in
 * tests that run in milliseconds and can put the aggregate in states a running system would take a fault
 * injector to reach.
 */
class PointsAccountTest {

  private static final PointsAccountId ID = new PointsAccountId("customer-1");

  private static PointsAccount fresh() {
    return PointsAccount.reconstitute(ID, 0, 0, null, 1);
  }

  private static PointsAccount holding(PointsAccount.Entry entry, int awarded, int frozen) {
    return PointsAccount.reconstitute(ID, awarded, frozen, entry, 1);
  }

  // --- AT's single operation -----------------------------------------------------------------------

  @Test
  void anawardAddsThePointsAndRecordsTheReference() {
    PointsAccount account = fresh();

    assertThat(account.award("ref-1", 25)).isEqualTo(AwardOutcome.AWARDED);
    assertThat(account.awarded()).isEqualTo(25);
    assertThat(account.entry()).map(PointsAccount.Entry::state)
        .contains(PointsAccount.EntryState.AWARDED);
  }

  @Test
  void thesameAwardTwiceAddsThePointsOnce() {
    PointsAccount account =
        holding(new PointsAccount.Entry("ref-1", 25, PointsAccount.EntryState.AWARDED), 25, 0);

    assertThat(account.award("ref-1", 25)).isEqualTo(AwardOutcome.ALREADY_AWARDED);
    assertThat(account.awarded()).isEqualTo(25);
  }

  // --- TCC's three ---------------------------------------------------------------------------------

  @Test
  void areservationFreezesThePointsWithoutAwardingThem() {
    PointsAccount account = fresh();

    assertThat(account.reserve("ref-2", 30)).isEqualTo(ReserveOutcome.RESERVED);
    // The distinction the whole TCC path rests on: promised is not the same as earned, and the model says so
    // in two different columns rather than in a lock nobody can read.
    assertThat(account.frozen()).isEqualTo(30);
    assertThat(account.awarded()).isZero();
  }

  @Test
  void confirmingMovesThePointsFromFrozenToAwarded() {
    PointsAccount account =
        holding(new PointsAccount.Entry("ref-2", 30, PointsAccount.EntryState.RESERVED), 0, 30);

    assertThat(account.confirmReservation("ref-2")).isEqualTo(SettleOutcome.SETTLED);
    assertThat(account.frozen()).isZero();
    assertThat(account.awarded()).isEqualTo(30);
  }

  @Test
  void confirmingTwiceMovesThePointsOnce() {
    PointsAccount account =
        holding(new PointsAccount.Entry("ref-2", 30, PointsAccount.EntryState.RESERVED), 0, 30);
    account.confirmReservation("ref-2");

    assertThat(account.confirmReservation("ref-2")).isEqualTo(SettleOutcome.ALREADY_SETTLED);
    assertThat(account.awarded()).isEqualTo(30);
  }

  @Test
  void cancellingGivesTheFrozenPointsBack() {
    PointsAccount account =
        holding(new PointsAccount.Entry("ref-2", 30, PointsAccount.EntryState.RESERVED), 0, 30);

    assertThat(account.cancelReservation("ref-2", 30)).isEqualTo(SettleOutcome.SETTLED);
    assertThat(account.frozen()).isZero();
    assertThat(account.awarded()).isZero();
  }

  @Test
  void cancellingTwiceGivesThemBackOnce() {
    PointsAccount account =
        holding(new PointsAccount.Entry("ref-2", 30, PointsAccount.EntryState.RESERVED), 0, 30);
    account.cancelReservation("ref-2", 30);

    assertThat(account.cancelReservation("ref-2", 30)).isEqualTo(SettleOutcome.ALREADY_CANCELLED);
    assertThat(account.frozen()).isZero();
  }

  /** Seata's empty rollback: the branch was rolled back before its Try ever ran. */
  @Test
  void cancellingSomethingThatWasNeverReservedIsFineAndStillLeavesAMark() {
    PointsAccount account = fresh();

    assertThat(account.cancelReservation("ref-3", 7)).isEqualTo(SettleOutcome.NOTHING_TO_SETTLE);
    assertThat(account.frozen()).isZero();
    // The mark is the load-bearing part. Without it the next test's Try would succeed.
    assertThat(account.entry()).map(PointsAccount.Entry::state)
        .contains(PointsAccount.EntryState.CANCELLED);
  }

  /** Seata's suspension: Try arrives after its own Cancel already ran. */
  @Test
  void areservationIsRefusedOnceItsReferenceHasBeenCancelled() {
    PointsAccount account = fresh();
    account.cancelReservation("ref-3", 7);

    assertThat(account.reserve("ref-3", 7)).isEqualTo(ReserveOutcome.CANCELLED_BEFORE_RESERVED);
    // Had this been allowed, 7 points would be frozen with no Confirm and no Cancel ever coming: the branch
    // is finished as far as the coordinator is concerned.
    assertThat(account.frozen()).isZero();
  }

  @Test
  void confirmingSomethingThatWasNeverReservedIsRefusedRatherThanInvented() {
    PointsAccount account = fresh();

    // The asymmetry with Cancel is the point: an absent reservation is ordinary for Cancel and never
    // ordinary for Confirm, so this one must not quietly succeed.
    assertThat(account.confirmReservation("ref-4")).isEqualTo(SettleOutcome.NOTHING_TO_SETTLE);
    assertThat(account.awarded()).isZero();
  }
}
