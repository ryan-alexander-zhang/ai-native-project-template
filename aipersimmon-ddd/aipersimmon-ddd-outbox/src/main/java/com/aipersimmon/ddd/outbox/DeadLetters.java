package com.aipersimmon.ddd.outbox;

import com.aipersimmon.ddd.cqrs.page.Cursor;
import com.aipersimmon.ddd.cqrs.page.Slice;
import java.util.Optional;

/**
 * Reads what the relay gave up on, so an operator can find a dead letter before replaying it.
 *
 * <p>This is the read side of {@link DeadLetterStore}, and a separate port on purpose. {@link
 * DeadLetterStore#replay} takes an {@code eventId} the caller must already have, which is only ever
 * true if they got it from somewhere — and until this port existed the only "somewhere" was a
 * hand-written query against a table the application does not own. Setting a message aside is worth
 * something only if it can be found again.
 *
 * <p>Split rather than merged into {@code DeadLetterStore} because the two have different
 * implementors. A store may legitimately hold nothing locally — its Javadoc invites overriding the
 * bean to raise an alert or forward to a quarantine topic — and such a store has no listing to
 * give. A storage starter registers both; a forwarding store leaves this one absent, and an
 * operations surface that requires it then fails to wire rather than pretending to be able to look.
 *
 * <p>Ordering is newest failure first, and paging is by opaque {@link Cursor} — the same shape the
 * read models of a consumer application use, so an operations endpoint pages dead letters exactly
 * as it pages anything else.
 */
public interface DeadLetters {

  /**
   * A page of dead letters, most recently failed first.
   *
   * @param after the cursor from a previous {@link Slice#nextCursor()}, or null for the first page.
   *     Cursors are opaque: pass back what was handed out, unchanged.
   * @param size how many to return; must be positive
   * @return the page, with a next cursor only when further dead letters exist
   * @throws IllegalArgumentException if {@code size} is not positive, or {@code after} is not a
   *     cursor this port issued
   */
  Slice<DeadLetter> list(Cursor after, int size);

  /**
   * One dead letter by event id — for an operator who arrived with an id from a log line or an
   * alert and wants to see why it failed before deciding to replay it.
   *
   * @param eventId the event id
   * @return the dead letter, or empty if none is held under that id
   */
  Optional<DeadLetter> find(String eventId);
}
