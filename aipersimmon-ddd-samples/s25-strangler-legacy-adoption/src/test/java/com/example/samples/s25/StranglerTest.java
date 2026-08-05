package com.example.samples.s25;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.core.exception.DomainException;
import com.example.samples.s25.acl.LegacyRefundEntryPoint;
import com.example.samples.s25.refunds.application.RefundQuery;
import com.example.samples.s25.refunds.application.RefundView;
import com.example.samples.s25.refunds.domain.RefundErrorCode;
import org.junit.jupiter.api.Test;

/**
 * The seam itself: the legacy signature, the new rules, and the one behaviour change.
 *
 * <p>What makes a strangler a strangler is that the callers do not change. Every test here calls the entry point with the
 * monolith's own arguments and return types, and the only differences observable from outside are the refusals — which are
 * the point of the exercise.
 */
class StranglerTest extends StranglerTestBase {

  /** The default route, so the assertions below are about the new path. */
  @Test
  void theseamIsRoutingToTheNewContext() {
    assertThat(entryPoint.route()).isEqualTo(LegacyRefundEntryPoint.Route.NEW_WRITES);
  }

  /** Same arguments, same return type, same row. Nothing a caller could notice. */
  @Test
  void thelegacySignatureStillWorksAndStillReturnsAnId() {
    long orderId = placeLegacyOrder(10_000);
    long refundId = entryPoint.raiseRefund(orderId, 2_500, "damaged");

    assertThat(refundId).isPositive();
    assertThat(refundRow(refundId))
        .containsEntry("order_id", orderId)
        .containsEntry("amount_cents", 2_500L)
        .containsEntry("state", "OPEN")
        .containsEntry("reason", "damaged");
  }

  /** The rule the monolith had as an {@code if}, now a refusal with a code. */
  @Test
  void arefundOnACancelledOrderIsRefusedWithACode() {
    long orderId = placeLegacyOrder(10_000);
    legacy.cancel(orderId);

    assertThatThrownBy(() -> entryPoint.raiseRefund(orderId, 100, "too late"))
        .isInstanceOf(DomainException.class)
        .satisfies(
            e ->
                assertThat(((DomainException) e).errorCode())
                    .contains(RefundErrorCode.ORDER_IS_CANCELLED));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_refunds", Long.class)).isZero();
  }

  /** The rule the monolith had as an unlocked comparison. */
  @Test
  void arefundLargerThanTheOrderIsRefused() {
    long orderId = placeLegacyOrder(10_000);
    assertThatThrownBy(() -> entryPoint.raiseRefund(orderId, 10_001, "generous"))
        .isInstanceOf(DomainException.class)
        .satisfies(
            e ->
                assertThat(((DomainException) e).errorCode())
                    .contains(RefundErrorCode.EXCEEDS_ORDER_TOTAL));
  }

  /**
   * <strong>The rule the monolith did not have at all.</strong>
   *
   * <p>Two open refunds on one order was always possible and nothing checked it. This is the only new rule the extraction
   * introduces, and introducing it is the interesting kind of risk: the old path allowed it, so there may be rows in
   * production that violate it. Which is a reason to check the data before shipping the rule, not a reason to skip the
   * rule — and the sample's V2 migration deliberately does <em>not</em> add a partial unique index, because a constraint
   * that rejects existing rows fails the deploy rather than the request.
   */
  @Test
  void asecondOpenRefundIsRefusedWhereTheMonolithAllowedIt() {
    long orderId = placeLegacyOrder(10_000);
    entryPoint.raiseRefund(orderId, 100, "first");

    assertThatThrownBy(() -> entryPoint.raiseRefund(orderId, 200, "second"))
        .isInstanceOf(DomainException.class)
        .satisfies(
            e ->
                assertThat(((DomainException) e).errorCode())
                    .contains(RefundErrorCode.ALREADY_OPEN));

    // And the old path still allows it, which is what makes the rule worth enforcing at the seam.
    long viaLegacy = legacy.raiseRefund(orderId, 200, "second, the old way");
    assertThat(refundRow(viaLegacy)).containsEntry("state", "OPEN");
    assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM legacy_refunds WHERE state = 'OPEN'", Long.class))
        .as("two open refunds, because the monolith never checked")
        .isEqualTo(2);
  }

  /**
   * The one behaviour change, with the old behaviour asserted next to it.
   *
   * <p>The legacy method turned a second approval into a silent no-op; the new one refuses. A caller that relied on the
   * silence now sees a conflict. Both are asserted here so the difference is written down — a behaviour change nobody
   * recorded is indistinguishable from a regression six months later.
   */
  @Test
  void asecondApprovalUsedToBeSilentAndIsNowARefusal() {
    long orderId = placeLegacyOrder(10_000);
    long refundId = entryPoint.raiseRefund(orderId, 2_500, "damaged");

    entryPoint.approveRefund(refundId, "ops-anna");
    assertThat(catchThrowable(() -> entryPoint.approveRefund(refundId, "ops-bob")))
        .as("the new path refuses")
        .isInstanceOf(DomainException.class);

    // What the monolith did with the same call, on a fresh refund.
    jdbc.update("UPDATE legacy_refunds SET state = 'REJECTED' WHERE id = ?", refundId);
    long another = legacy.raiseRefund(orderId, 100, "another");
    legacy.approveRefund(another, "ops-anna");
    assertThat(catchThrowable(() -> legacy.approveRefund(another, "ops-bob")))
        .as("the old path said nothing at all, and the second caller believed it had worked")
        .isNull();
    assertThat(refundRow(another)).containsEntry("approved_by", "ops-anna");
  }

  /** An unknown order is a not-found from the ACL rather than a JDBC exception from the monolith. */
  @Test
  void anunknownOrderIsANotFoundAndNotAJdbcException() {
    assertThatThrownBy(() -> entryPoint.raiseRefund(999_999, 100, "nobody's order"))
        .isInstanceOf(EntityNotFoundException.class)
        .satisfies(
            e ->
                assertThat(((EntityNotFoundException) e).errorCode())
                    .contains(RefundErrorCode.ORDER_NOT_FOUND));
  }

  /** Reading it back through the new context gives both identities, and leads with the durable one. */
  @Test
  void thereadModelLeadsWithTheDurableIdentity() {
    long orderId = placeLegacyOrder(10_000);
    long refundId = entryPoint.raiseRefund(orderId, 2_500, "damaged");

    RefundView view = queryBus.ask(new RefundQuery(refundId));
    assertThat(view.id()).isEqualTo(refundId);
    assertThat(view.publicId()).isNotBlank().isNotEqualTo(Long.toString(refundId));
    assertThat(view.state()).isEqualTo("OPEN");
    assertThat(view.approvedBy()).isNull();
  }

  /** And a row the monolith created is readable through the new context, which is what keeps the migration gradual. */
  @Test
  void arowCreatedByTheMonolithIsReadableThroughTheNewContext() {
    long orderId = placeLegacyOrder(10_000);
    long refundId = legacy.raiseRefund(orderId, 2_500, "raised before the migration");

    RefundView view = queryBus.ask(new RefundQuery(refundId));
    assertThat(view.amountCents()).isEqualTo(2_500);
    assertThat(view.publicId()).isNotBlank();

    entryPoint.approveRefund(refundId, "ops-anna");
    assertThat(refundRow(refundId)).containsEntry("state", "APPROVED");
    assertThat(refundVersion(refundId))
        .as("a pre-existing row starts at the column default of 1 and advances from there")
        .isEqualTo(2);
  }
}
