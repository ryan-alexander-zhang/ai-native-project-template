package com.example.samples.s27;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.aipersimmon.ddd.core.error.FailureSummary;
import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import com.example.samples.s27.customer.application.SuppressCustomer;
import com.example.samples.s27.customer.domain.Customer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one line of the library nobody had run, and the case it does not cover.
 *
 * <p>Saving an aggregate is never a partial update, so the library forces an explicit {@code column = null} for
 * every column {@code toRow} left empty — otherwise a field the aggregate cleared would keep its old value with
 * everything reporting success ({@code ClearedColumns}'s javadoc argues this at length). A delete flag is a
 * column {@code toRow} always leaves empty, because the aggregate does not own it. So the two mechanisms meet,
 * and the library has one line about it:
 *
 * <pre>{@code
 * || (tableInfo.isWithLogicDelete() && field.isLogicDelete());
 * }</pre>
 *
 * <p>Before this sample, nothing in the repository used {@code @TableLogic} — it was the only mention of the
 * annotation anywhere — so that exclusion had never been exercised.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import({PostgresServiceConnection.class, HandRolledFlag.class})
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class ClearedColumnsTest extends CustomerTestBase {

  @Autowired private HandRolledFlag.HandRolledCustomers handRolled;
  @Autowired private PlatformTransactionManager transactions;

  /**
   * With the annotation: an ordinary save leaves the flag alone.
   *
   * <p>The library's exclusion, working. {@code toRow} says nothing about {@code deleted} and the update does not
   * force it to null — so the column keeps whatever the infrastructure put there, which is the only correct
   * behaviour for a column the domain has no opinion about.
   */
  @Test
  void anordinarySaveDoesNotTouchTheDeleteFlag() {
    registerAlice();
    assertThat(rawRow(ALICE).get("deleted")).isEqualTo(false);

    close(ALICE, "a perfectly ordinary write");

    assertThat(rawRow(ALICE).get("deleted")).isEqualTo(false);
    assertThat(rawRow(ALICE).get("status")).isEqualTo("CLOSED");
  }

  /**
   * And the columns the aggregate really did clear <em>are</em> forced, which is what the exclusion has to not
   * break.
   *
   * <p>Alice has a phone number; the erasure clears it. Without the forcing the {@code SET} clause would omit it
   * and the old number would survive an operation whose entire purpose was to remove it — a privacy failure with
   * a successful commit, a moved version and a published event. This is the assertion that the exclusion is
   * narrow rather than a blanket "skip nulls".
   */
  @Test
  void theemptiedColumnsAreStillForced() {
    registerAlice();
    assertThat(rawRow(ALICE).get("phone")).isEqualTo("+44 7700 900000");
    drainTheOutbox();

    erase(ALICE);

    assertThat(rawRow(ALICE).get("phone")).isNull();
    assertThat(rawRow(ALICE).get("deleted")).isEqualTo(false);
  }

  /**
   * Without the annotation: the same omission is a write of {@code deleted = null}.
   *
   * <p>The trap. The hand-rolled row class treats the flag as an ordinary column, so the library — correctly, by
   * its own rules — decides that an unmapped column means "the aggregate emptied this" and forces it to null. The
   * schema's {@code NOT NULL} is what turns that into a visible failure, and the blast radius is total rather than
   * local: measured by taking {@code @TableLogic} off the real row class, <strong>22 of this sample's 42 tests go
   * red and every one of them on this same constraint</strong> — not "suppressed rows misbehave" but "no write
   * succeeds at all". Which is the good outcome. It is the nullable variant below that is dangerous.
   *
   * <p><strong>What the NOT NULL is buying, precisely:</strong> the statement being built is
   * {@code SET ..., deleted = null WHERE id = ? AND version = ?}. On a nullable column that commits, and the row
   * ends up with a delete flag that is neither true nor false — invisible to a {@code deleted = false} filter and
   * to a {@code deleted = true} one alike, so neither suppressed nor live, and reachable only by SQL that knows
   * to look for null. Not measured here (this column is NOT NULL and the sample would rather not mutate its own
   * schema to prove it), but it follows from the statement, and it is the reason a delete flag should be
   * {@code NOT NULL DEFAULT} in every schema whether or not anything currently forces it.
   */
  @Test
  void withoutTheAnnotationTheSameOmissionWritesNull() {
    registerAlice();
    commandBus.send(new SuppressCustomer(ALICE));
    assertThat(rawRow(ALICE).get("deleted")).isEqualTo(true);

    // The hand-rolled mapper has no logic-delete filter either, so unlike the real one it can load a hidden
    // row — which is how a team gets here: everything appears to work until something writes.
    Customer loaded = loadThroughTheHandRolledPath();

    Throwable failure =
        catchThrowable(
            () ->
                new TransactionTemplate(transactions)
                    .executeWithoutResult(status -> handRolled.save(loaded)));

    assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
    // The outer message is empty — the useful half is two levels down, which is the same shape S22 filed as
    // issue-00165 about the outbox's own error recording. FailureSummary is the library's answer to it, and it
    // is used here to read the chain rather than to record one.
    String chain = FailureSummary.of(failure);
    assertThat(chain).contains("null value in column").contains("deleted");

    // Nothing was written: the row is still hidden.
    assertThat(rawRow(ALICE).get("deleted")).isEqualTo(true);
  }

  /**
   * Loads through a statement with no logic-delete filter, so a hidden row comes back.
   *
   * <p>Done with SQL rather than the hand-rolled mapper to keep the test's subject singular: what is being
   * measured is the <em>write</em>, and going through {@code selectById} on the hand-rolled entity would also be
   * demonstrating that its reads see hidden rows — true, and a different sentence.
   */
  private Customer loadThroughTheHandRolledPath() {
    return jdbc.queryForObject(
        "SELECT id, email, display_name, phone, status, closed_reason, erased_at, version"
            + " FROM s27_customer WHERE id = ?",
        (rs, rowNum) ->
            Customer.reconstitute(
                id(rs.getString("id")),
                new com.example.samples.s27.customer.domain.EmailAddress(rs.getString("email")),
                rs.getString("display_name"),
                rs.getString("phone"),
                com.example.samples.s27.customer.domain.CustomerStatus.valueOf(rs.getString("status")),
                rs.getString("closed_reason"),
                rs.getTimestamp("erased_at") == null
                    ? null
                    : rs.getTimestamp("erased_at").toInstant(),
                rs.getLong("version")),
        ALICE);
  }
}
