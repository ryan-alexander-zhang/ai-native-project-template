package com.example.samples.s01;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.samples.s01.ordering.application.ConfirmOrder;
import com.example.samples.s01.ordering.application.PlaceOrder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The two transaction semantics, and why they are different on purpose.
 *
 * <p>A success row commits <em>with</em> the business change: the log must not claim something happened that
 * then rolled back. A failure row commits on its own: the transaction it would have shared has gone, and a
 * failure that erased its own audit trail would make the log answer "nothing was attempted".
 */
class AuditTransactionTest extends AuditTestBase {

  @Autowired private PlatformTransactionManager transactions;

  /**
   * Roll the business transaction back and the audit row goes with it.
   *
   * <p>Which is the property worth having: an audit trail whose rows can outlive the changes they describe is
   * worse than none, because every row then has to be checked against the data before it can be believed.
   */
  @Test
  void asuccessRowSharesTheBusinessTransactionAndRollsBackWithIt() {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              commandBus.send(new PlaceOrder("customer-1", lines()));
              // Both the order and its audit row exist here, in this transaction.
              assertThat(orderCount()).isEqualTo(1);
              assertThat(auditRowCount()).isEqualTo(1);
              status.setRollbackOnly();
            });

    assertThat(orderCount()).isZero();
    assertThat(auditRowCount()).isZero();
  }

  /**
   * A failure row survives the rollback, because it is written in a transaction of its own.
   *
   * <p>Confirming an already-confirmed order is a domain refusal: the command's transaction rolls back and
   * the caller gets a 409. The audit row is still there afterwards — which is the only reason the log can
   * answer "who tried to confirm this twice".
   */
  @Test
  void afailureRowIsWrittenInItsOwnTransactionAndSurvives() {
    String orderId = commandBus.send(new PlaceOrder("customer-1", lines()));
    commandBus.send(new ConfirmOrder(orderId));
    jdbc.update("DELETE FROM aipersimmon_operation_log");

    assertThatThrownBy(() -> commandBus.send(new ConfirmOrder(orderId))).isInstanceOf(Exception.class);

    Map<String, Object> row = onlyAuditRow();
    assertThat(row.get("operation_code")).isEqualTo("ordering.order.confirm");
    assertThat(row.get("target_id")).isEqualTo(orderId);
    assertThat((String) row.get("summary")).contains("Could not confirm order");
    // REJECTED, not FAILED: the business refused, nothing broke. The classifier reads the context's own
    // ErrorCode off the DomainException, so the audit row carries the same code the HTTP problem document
    // does — "ordering.order-not-confirmable" — and an auditor can join the two without a mapping table.
    assertThat(row.get("outcome")).isEqualTo("REJECTED");
    assertThat(row.get("failure_code")).isEqualTo("ordering.order-not-confirmable");
    assertThat(row.get("failure_category")).isEqualTo("CONFLICT");
    // Work began and was undone.
    assertThat(row.get("completion")).isEqualTo("ROLLED_BACK");
  }

  /**
   * A command rejected before its transaction started is recorded as {@code NOT_STARTED}, not as a rollback.
   *
   * <p>{@code outcome} is the same as the test above — both are refusals, not breakages — and it is
   * {@code completion} that separates them: {@code ROLLED_BACK} says work began and was undone,
   * {@code NOT_STARTED} says nothing was attempted. An auditor reading "rolled back" for a validation
   * failure would go looking for the partial effects of something that never ran, which is why the library
   * keeps two columns here instead of collapsing them into one status.
   *
   * <p>{@code FAILED} is the third case and is deliberately not exercised here: per
   * {@code DefaultFailureClassifier}, it covers a concurrency conflict and anything unexpected — a broken
   * database, a bug — and provoking one would mean breaking the infrastructure mid-command for the sake of
   * an enum value. What matters for the taxonomy is that a bad request does <em>not</em> land there, and
   * both tests assert that.
   */
  @Test
  void avalidationRejectionIsRecordedAsNotStarted() {
    assertThatThrownBy(() -> commandBus.send(new ConfirmOrder("  ")))
        .isInstanceOf(Exception.class);

    Map<String, Object> row = onlyAuditRow();
    assertThat(row.get("outcome")).isEqualTo("REJECTED");
    assertThat(row.get("failure_category")).isEqualTo("VALIDATION");
    assertThat(row.get("completion")).isEqualTo("NOT_STARTED");
  }

  /**
   * A failed create records nothing at all, and that is a decision with a named fix.
   *
   * <p>{@code PlaceOrderAudit.failed} returns empty because a create that failed has no target id, and
   * {@code Target} requires one. The fix is to mint the identity before the command rather than inside the
   * handler; until then, "who tried to place an order and could not" is a question this log cannot answer,
   * while the same question about {@code confirm} is answered by the test above.
   *
   * <p>Asserted rather than left implicit, so that a later change which starts recording failed creates
   * breaks this test and gets read.
   */
  @Test
  void afailedCreateRecordsNothingBecauseItHasNoTarget() {
    assertThatThrownBy(() -> commandBus.send(new PlaceOrder("customer-1", List.of())))
        .isInstanceOf(Exception.class);

    assertThat(auditRowCount()).isZero();
    assertThat(orderCount()).isZero();
  }

  private static List<PlaceOrder.Line> lines() {
    return List.of(new PlaceOrder.Line("SKU-1", 2));
  }
}
