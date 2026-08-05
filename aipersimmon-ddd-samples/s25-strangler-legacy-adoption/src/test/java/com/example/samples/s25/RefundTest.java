package com.example.samples.s25;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.core.exception.DomainException;
import com.example.samples.s25.refunds.domain.Refund;
import com.example.samples.s25.refunds.domain.RefundErrorCode;
import com.example.samples.s25.refunds.domain.RefundId;
import com.example.samples.s25.refunds.domain.RefundState;
import org.junit.jupiter.api.Test;

/**
 * The aggregate, with no monolith anywhere in sight — which is the point of it existing.
 *
 * <p>Every rule the monolith had is testable here in milliseconds, with the order's facts supplied as arguments. Before the
 * extraction the same rules needed a database, an order row, and a service; that difference is most of what "extract an
 * aggregate" buys on a day-to-day basis.
 */
class RefundTest {

  private static final RefundId ID = new RefundId(42);

  private static Refund raised() {
    return Refund.raise(ID, 7L, 2_500, "damaged", false, 10_000, false);
  }

  @Test
  void arefundIsOpenAndKnowsWhatItIsFor() {
    Refund refund = raised();
    assertThat(refund.state()).isEqualTo(RefundState.OPEN);
    assertThat(refund.orderId()).isEqualTo(7L);
    assertThat(refund.amountCents()).isEqualTo(2_500);
    assertThat(refund.reason()).contains("damaged");
    assertThat(refund.publicId()).as("an outward identity, minted here").isNotNull();
    assertThat(refund.approvedBy()).isEmpty();
  }

  @Test
  void acancelledOrderCannotBeRefunded() {
    assertThatThrownBy(() -> Refund.raise(ID, 7L, 100, "late", true, 10_000, false))
        .isInstanceOf(DomainException.class)
        .satisfies(
            e ->
                assertThat(((DomainException) e).errorCode())
                    .contains(RefundErrorCode.ORDER_IS_CANCELLED));
  }

  @Test
  void arefundCannotExceedTheOrder() {
    assertThatThrownBy(() -> Refund.raise(ID, 7L, 10_001, "generous", false, 10_000, false))
        .isInstanceOf(DomainException.class)
        .hasMessageContaining("exceeds order 7's total of 10000");
  }

  /** The rule the monolith never had. */
  @Test
  void asecondOpenRefundIsRefused() {
    assertThatThrownBy(() -> Refund.raise(ID, 7L, 100, "second", false, 10_000, true))
        .isInstanceOf(DomainException.class)
        .satisfies(
            e ->
                assertThat(((DomainException) e).errorCode())
                    .contains(RefundErrorCode.ALREADY_OPEN));
  }

  @Test
  void arefundMustBeForSomething() {
    assertThatThrownBy(() -> Refund.raise(ID, 7L, 0, "nothing", false, 10_000, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive amount");
  }

  /** A refusal where the monolith went quiet. */
  @Test
  void asecondApprovalIsRefusedAndSoIsApprovingARejectedOne() {
    Refund approved = raised();
    approved.approve("ops-anna");
    assertThat(approved.state()).isEqualTo(RefundState.APPROVED);
    assertThat(approved.approvedBy()).contains("ops-anna");
    assertThatThrownBy(() -> approved.approve("ops-bob"))
        .isInstanceOf(DomainException.class)
        .satisfies(
            e ->
                assertThat(((DomainException) e).errorCode())
                    .contains(RefundErrorCode.ALREADY_CLOSED));

    Refund rejected = raised();
    rejected.reject("not our fault");
    assertThatThrownBy(() -> rejected.approve("ops-anna")).isInstanceOf(DomainException.class);
  }

  @Test
  void anapprovalNeedsAnApprover() {
    assertThatThrownBy(() -> raised().approve(" "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("needs an approver");
  }

  /** A rejection clears the approver, which is a null the framework has to write. */
  @Test
  void arejectionClearsTheApprover() {
    Refund refund =
        Refund.reconstitute(
            ID, 7L, 2_500, "damaged", java.util.UUID.randomUUID(), "OPEN", "ops-anna", 3);
    refund.reject("changed our mind");
    assertThat(refund.state()).isEqualTo(RefundState.REJECTED);
    assertThat(refund.approvedBy()).isEmpty();
  }

  /** The states are the legacy column's strings, not an improved set. */
  @Test
  void thestatesAreStillTheLegacyStrings() {
    assertThat(RefundState.values())
        .extracting(Enum::name)
        .containsExactly("OPEN", "APPROVED", "REJECTED");
  }
}
