package com.example.samples.s27;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.samples.s27.customer.application.ChangeEmail;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The audit log, where the erasure obligation and the retention obligation point in opposite directions.
 *
 * <p>The resolution is not a clever purge. It is that <strong>what an audit row contains was decided when it was
 * written</strong>: the component's pipeline strips control characters and bounds lengths, and nothing in it removes
 * personal data — {@code Redactor}'s own javadoc says the allowlist is the definition's job. There is no update port
 * and no delete-by-id, so a row that captured an address keeps it until retention expires.
 */
class ErasureAndAuditTest extends CustomerTestBase {

  /**
   * The erasure's own audit row survives the erasure, and has to.
   *
   * <p>It is the evidence the obligation was discharged. An erasure that removed its own audit trail would leave the
   * service unable to prove it had complied — and unable to distinguish "we erased this person" from "we lost this
   * person's record".
   */
  @Test
  void theerasuresOwnRowSurvivesAndNamesTheTicket() {
    registerAlice();
    drainTheOutbox();

    erase(ALICE);

    Map<String, Object> row = auditRowsFor("customer.erase").get(0);
    assertThat(row.get("target_id")).isEqualTo(ALICE);
    assertThat(row.get("outcome")).isEqualTo("SUCCEEDED");
    assertThat((String) row.get("summary")).contains("TICKET-42");
    // And it says nothing the erasure was supposed to remove.
    assertThat((String) row.get("summary")).doesNotContain(ALICE_EMAIL);
    assertThat((String) row.get("summary")).doesNotContain("Alice Example");
  }

  /**
   * An earlier row about the same customer keeps its masked value, and the erasure does not touch it.
   *
   * <p>{@code mask} is what makes that acceptable: the row records that the address changed and to something ending
   * in a particular character, which is what a support investigation needs, and is not the address. Had the template
   * said "from A to B" — the obvious phrasing — this row would now hold two addresses that an erasure has no port to
   * remove.
   */
  @Test
  void anearlierRowKeepsAmaskedValueAndIsNotRewritten() {
    registerAlice();
    commandBus.send(new ChangeEmail(ALICE, "alice.new@example.com"));
    drainTheOutbox();

    erase(ALICE);

    Map<String, Object> row = auditRowsFor("customer.email.change").get(0);
    assertThat((String) row.get("summary")).contains(ALICE);
    assertThat((String) row.get("summary")).doesNotContain("alice.new@example.com");
    // First character, three stars, last character.
    assertThat((String) row.get("summary")).contains("a***m");
  }

  /**
   * The switch is audited, which is the condition on using it at all.
   *
   * <p>A logical delete leaves no other trace: no event, no state, and the row itself vanishes from every read. If
   * this row did not exist, "who hid this customer, and when" would be unanswerable.
   */
  @Test
  void suppressingArowIsAudited() {
    registerAlice();

    commandBus.send(new com.example.samples.s27.customer.application.SuppressCustomer(ALICE));

    Map<String, Object> row = auditRowsFor("customer.suppress").get(0);
    assertThat(row.get("target_id")).isEqualTo(ALICE);
    assertThat((String) row.get("summary")).contains("infrastructure, not a business state");
  }

  /**
   * Closing records its reason, so the domain deletion is explicable from the audit trail as well as from the row.
   */
  @Test
  void closingRecordsTheReason() {
    registerAlice();

    close(ALICE, "moved to a competitor");

    assertThat((String) auditRowsFor("customer.close").get(0).get("summary"))
        .contains("moved to a competitor");
  }

  /**
   * A refused erasure is audited too, and this is where the classifier's taxonomy is measured rather than assumed.
   *
   * <p>The refusal is an {@code ApplicationException} carrying this context's own {@code ErrorCode} — the library's
   * own base type for, in its words, "a conflicting request". The values asserted below are what the component
   * actually records for it, not what seemed likely; the first version of this test guessed and was wrong, and §8 of
   * the companion document is about the gap that turned up.
   */
  @Test
  void arefusedErasureIsAudited() {
    registerAlice();

    assertThatThrownBy(() -> erase(ALICE)).hasMessageContaining("have not been delivered yet");

    Map<String, Object> row = auditRowsFor("customer.erase").get(0);
    assertThat(row.get("target_id")).isEqualTo(ALICE);
    assertThat((String) row.get("summary")).contains("Could not erase");
    assertThat(row.get("outcome")).isEqualTo("FAILED");
    assertThat(row.get("failure_code")).isEqualTo("unexpected");
    assertThat(row.get("failure_category")).isEqualTo("UNEXPECTED");
  }
}
