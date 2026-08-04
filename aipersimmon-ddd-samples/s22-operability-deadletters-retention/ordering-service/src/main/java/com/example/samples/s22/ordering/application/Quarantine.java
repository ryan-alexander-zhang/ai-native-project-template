package com.example.samples.s22.ordering.application;

import com.aipersimmon.ddd.cqrs.page.Cursor;
import com.aipersimmon.ddd.cqrs.page.Slice;
import com.aipersimmon.ddd.outbox.DeadLetter;
import com.aipersimmon.ddd.outbox.DeadLetterStore;
import com.aipersimmon.ddd.outbox.DeadLetters;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The operations surface over messages this service gave up delivering.
 *
 * <p>Three methods, and the shape is the argument. {@link DeadLetterStore#replay} takes an event id
 * the caller must already have, which is only ever true if they got it from somewhere — and the
 * library splits the read side ({@link DeadLetters}) from the store precisely because, before that
 * port existed, the only "somewhere" was a hand-written query against a table the application does
 * not own. Setting a message aside is worth nothing if it cannot be found again, so a service that
 * ships an outbox and no listing has quarantined its messages into a place with no door.
 *
 * <p><strong>Why an application service and not a controller with two injected ports.</strong>
 * Because replay is a decision, not a lookup, and it has a precondition that belongs in one place:
 * the cause must have been fixed first. Nothing here can check that — no code can — so the honest
 * design states it, exposes the evidence an operator needs to judge it ({@code reason}, {@code
 * attempts}, {@code lastError}), and makes replaying a bad message harmless rather than impossible.
 * That is what {@link #replay} leans on: a replay whose cause is still broken simply fails again and
 * comes back here, having spent its attempts and nothing else.
 *
 * <p><strong>What makes a replay safe to press twice.</strong> Not this class. The event keeps its
 * original id when it goes back into the outbox, so a consumer that already saw it recognises the
 * duplicate through its inbox — the same {@code (source, ce_id)} key that absorbs the outbox's own
 * at-least-once redeliveries. Replay does not need a new idempotency mechanism because it produces
 * exactly the kind of duplicate the system was already built to survive. A "replay" that minted a
 * fresh event id would be a second event about the same fact, and no downstream dedup could catch it.
 */
@Service
public class Quarantine {

  private final DeadLetters deadLetters;
  private final DeadLetterStore store;

  Quarantine(DeadLetters deadLetters, DeadLetterStore store) {
    this.deadLetters = deadLetters;
    this.store = store;
  }

  /**
   * Newest give-up first, paged by opaque cursor — the same paging shape as any other read model
   * (S20), so an operations screen is not a special case.
   */
  public Slice<DeadLetter> list(Cursor after, int size) {
    return deadLetters.list(after, size);
  }

  /** One dead letter, for an operator who arrived with an id from a log line or an alert. */
  public Optional<DeadLetter> find(String eventId) {
    return deadLetters.find(eventId);
  }

  /**
   * Puts the message back in the outbox, unsent and with its attempt count reset, for the relay to
   * pick up on its next poll.
   *
   * <p>Returns false when no dead letter is held under that id, which is also the answer to pressing
   * the button twice: the first replay moved the row out, so the second finds nothing. Idempotent by
   * consequence rather than by a guard.
   */
  public boolean replay(String eventId) {
    return store.replay(eventId);
  }
}
