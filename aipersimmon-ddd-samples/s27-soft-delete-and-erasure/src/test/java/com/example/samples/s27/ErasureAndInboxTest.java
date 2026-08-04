package com.example.samples.s27;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.samples.s27.customer.application.AbsorbMarketingSignal;
import com.example.samples.s27.customer.application.MarketingConsents;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The inbox, where the instinct to delete everything that mentions the customer does real damage for no privacy
 * gain.
 *
 * <p>The catalogue asks what an erasure means for the inbox's idempotency keys. The answer is <strong>nothing, and
 * the reason is worth being precise about</strong>: the table has no column that identifies a person, so the
 * erasure could not target those rows even if it wanted to — and a well-meaning purge by time window instead breaks
 * the one thing the table is for.
 */
class ErasureAndInboxTest extends CustomerTestBase {

  private static final String SOURCE = "/crm";
  private static final String MESSAGE = "crm-msg-1";

  @Autowired private MarketingConsents consents;

  /**
   * There is no customer column. The whole table is {@code (consumer, source, message_key, processed_at, tenant_id)}.
   *
   * <p>Asserted over the live schema rather than quoted from the DDL, because this is the load-bearing fact: an
   * erasure that wanted to remove "this person's inbox rows" has no predicate to write. The keys are message ids
   * minted by the producer, which are not personal data — they identify a delivery, not a subject.
   */
  @Test
  void theinboxHasNoColumnThatNamesAPerson() {
    List<String> columns =
        jdbc.queryForList(
            "SELECT column_name FROM information_schema.columns WHERE table_name = 'aipersimmon_inbox'"
                + " ORDER BY column_name",
            String.class);

    assertThat(columns)
        .containsExactly("consumer", "message_key", "processed_at", "source", "tenant_id");
  }

  /** The mechanism, so the next test measures something. */
  @Test
  void aredeliveredMessageIsAbsorbedOnce() {
    registerAlice();

    assertThat(commandBus.send(new AbsorbMarketingSignal(SOURCE, MESSAGE, ALICE, "newsletter"))).isTrue();
    assertThat(commandBus.send(new AbsorbMarketingSignal(SOURCE, MESSAGE, ALICE, "newsletter"))).isFalse();

    assertThat(consents.countFor(id(ALICE))).isEqualTo(1);
    assertThat(inboxRowCount()).isEqualTo(1);
  }

  /**
   * And what a purge by time window costs: the same message is processed a second time.
   *
   * <p>This is the shape of the mistake — "delete anything older than the erasure request, to be safe" — and it is
   * not a privacy improvement, because nothing personal was in those rows. It is a silent loss of exactly-once
   * processing, and the symptom appears whenever the producer happens to redeliver, which may be weeks later.
   */
  @Test
  void deletingInboxRowsMakesAredeliveryProcessAgain() {
    registerAlice();
    commandBus.send(new AbsorbMarketingSignal(SOURCE, MESSAGE, ALICE, "newsletter"));
    assertThat(consents.countFor(id(ALICE))).isEqualTo(1);

    jdbc.update("DELETE FROM aipersimmon_inbox");

    assertThat(commandBus.send(new AbsorbMarketingSignal(SOURCE, MESSAGE, ALICE, "newsletter"))).isTrue();
    assertThat(consents.countFor(id(ALICE))).isEqualTo(2);
  }

  /**
   * The erasure leaves the inbox alone and removes the consent rows, which is the right way round.
   *
   * <p>The two tables are next to each other and their answers are opposite, which is the whole lesson: a consent
   * row names a person and nobody needs to prove one ever existed, so it goes. An inbox row names a message and its
   * absence breaks a correctness mechanism, so it stays. "Delete everything about the customer" gets one of the two
   * wrong.
   */
  @Test
  void theerasureForgetsTheConsentsAndKeepsTheKeys() {
    registerAlice();
    commandBus.send(new AbsorbMarketingSignal(SOURCE, MESSAGE, ALICE, "newsletter"));
    drainTheOutbox();

    erase(ALICE);

    assertThat(consents.countFor(id(ALICE))).isZero();
    assertThat(inboxRowCount()).isEqualTo(1);
  }

  /**
   * Which means a redelivery after the erasure is still suppressed — and that is a feature, not an oversight.
   *
   * <p>Had the keys been purged, a redelivered signal would have re-created a consent row for a customer whose data
   * was erased: the erasure would have been undone by a message that arrived late. Keeping the keys is what makes
   * the erasure durable against the transport.
   */
  @Test
  void aredeliveryAfterTheErasureCannotRecreateAnything() {
    registerAlice();
    commandBus.send(new AbsorbMarketingSignal(SOURCE, MESSAGE, ALICE, "newsletter"));
    drainTheOutbox();
    erase(ALICE);

    assertThat(commandBus.send(new AbsorbMarketingSignal(SOURCE, MESSAGE, ALICE, "newsletter"))).isFalse();
    assertThat(consents.countFor(id(ALICE))).isZero();
  }
}
