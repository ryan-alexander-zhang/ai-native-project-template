package com.example.samples.s27;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.samples.s27.customer.application.ChangeEmail;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What an erasure has to do about announcements it already promised to send.
 *
 * <p>The hardest of the catalogue's questions, and the one with no comfortable answer: an unsent outbox row about
 * this customer contains their address, and after the erasure there is no correct thing to do with it. So the
 * ordering is arranged instead.
 */
class ErasureAndOutboxTest extends CustomerTestBase {

  /** First, the fact that makes it a problem: the queued payloads really do quote the person. */
  @Test
  void thequeuedAnnouncementsContainThePersonalData() {
    registerAlice();
    commandBus.send(new ChangeEmail(ALICE, "alice.new@example.com"));

    List<Map<String, Object>> rows = outboxRows();
    assertThat(rows).hasSize(2);
    assertThat((String) rows.get(0).get("payload")).contains(ALICE_EMAIL).contains("Alice Example");
    assertThat((String) rows.get(1).get("payload")).contains("alice.new@example.com");
    assertThat(rows).allSatisfy(row -> assertThat(row.get("sent")).isEqualTo(false));
  }

  /**
   * So the erasure refuses while any of them is unsent.
   *
   * <p>A 409 rather than a best guess. The three alternatives are all worse than a retry: publish the data after
   * the moment it was to be gone, drop a change every consumer already depends on, or rewrite a published
   * contract with values that were never true.
   */
  @Test
  void theerasureRefusesWhileAnnouncementsAreQueued() {
    registerAlice();

    assertThatThrownBy(() -> erase(ALICE))
        .hasMessageContaining("have not been delivered yet")
        .hasMessageContaining("Let the relay drain and retry");

    // Nothing happened: not a partial erasure.
    assertThat(rawRow(ALICE).get("email")).isEqualTo(ALICE_EMAIL);
    assertThat(rawRow(ALICE).get("erased_at")).isNull();
  }

  /** And proceeds once the queue is drained. */
  @Test
  void theerasureProceedsOnceTheQueueIsDrained() {
    registerAlice();
    drainTheOutbox();

    erase(ALICE);

    assertThat(rawRow(ALICE).get("erased_at")).isNotNull();
  }

  /**
   * The refusal is scoped to this customer, not to the whole queue.
   *
   * <p>Which matters at any real volume: a service with a busy outbox would never have an entirely empty queue, so
   * a gate on "anything unsent" would make erasure impossible rather than ordered. The subject column is what makes
   * the narrow question askable, and it is indexed — {@code idx_aipersimmon_outbox_subject_order} — because the
   * relay needs the same question for ordering.
   */
  @Test
  void anothercustomersQueueDoesNotBlockThisErasure() {
    registerAlice();
    register("cust-bob", "bob@example.com");
    jdbc.update("UPDATE aipersimmon_outbox SET sent = TRUE, sent_at = now() WHERE subject = ?", ALICE);

    erase(ALICE);

    assertThat(rawRow(ALICE).get("erased_at")).isNotNull();
    // Bob's announcement is still queued and was never in question.
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM aipersimmon_outbox WHERE subject = ? AND sent = FALSE",
                Long.class,
                "cust-bob"))
        .isEqualTo(1);
  }

  /**
   * The erasure publishes an instruction, and it carries nothing to erase.
   *
   * <p>Overwriting the local columns discharges the obligation in exactly one database. Every consumer that kept a
   * copy — which is what the registration event invited them to do — has the same duty and no other way to learn of
   * it.
   */
  @Test
  void theerasureAnnouncesItselfWithoutQuotingAnybody() {
    registerAlice();
    drainTheOutbox();

    erase(ALICE);

    Map<String, Object> announcement =
        outboxRows().stream()
            .filter(row -> row.get("sent").equals(false))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the erasure announced nothing"));
    assertThat(announcement.get("type")).isEqualTo("com.example.samples.customers.CustomerErased");
    assertThat(announcement.get("subject")).isEqualTo(ALICE);
    String payload = (String) announcement.get("payload");
    assertThat(payload).contains(ALICE).contains("erasedAt");
    assertThat(payload).doesNotContain(ALICE_EMAIL);
    assertThat(payload).doesNotContain("Alice Example");
    assertThat(payload).doesNotContain("7700");
  }

  /**
   * A second erasure request is a no-op, and does not announce again.
   *
   * <p>An erasure request arrives more than once — a retry, a second letter, a replayed message — and the second one
   * finding nothing to do is the correct outcome. It also must not re-announce: a consumer that already discharged
   * its own obligation would be asked to do it again, which is harmless, and would also see a second erasure date
   * for the same person, which is not.
   */
  @Test
  void asecondErasureChangesNothingAndSaysNothing() {
    registerAlice();
    drainTheOutbox();
    erase(ALICE);
    Object firstDate = rawRow(ALICE).get("erased_at");
    drainTheOutbox();

    erase(ALICE);

    assertThat(rawRow(ALICE).get("erased_at")).isEqualTo(firstDate);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM aipersimmon_outbox WHERE type = ?",
                Long.class,
                "com.example.samples.customers.CustomerErased"))
        .isEqualTo(1);
  }
}
