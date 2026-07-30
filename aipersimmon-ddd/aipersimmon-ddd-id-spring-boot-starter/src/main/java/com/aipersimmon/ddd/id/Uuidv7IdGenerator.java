package com.aipersimmon.ddd.id;

import com.aipersimmon.ddd.core.id.IdGenerator;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.UUIDClock;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;

/**
 * The default {@link IdGenerator}: a time-ordered UUIDv7 (RFC 9562) rendered as a 36-char string, a
 * drop-in for {@code UUID.randomUUID().toString()} that inserts near the tail of an index instead
 * of scattering random writes.
 *
 * <p>Backed by JUG's {@link Generators#timeBasedEpochGenerator()}: within a single millisecond it
 * increments the random component of the previous value rather than drawing a fresh one, so a burst
 * of ids stays strictly increasing and keeps its index locality. The underlying generator guards
 * its state with a lock, so a single shared instance is safe to call from many threads; this class
 * holds exactly one.
 *
 * <p><strong>Ordering follows the wall clock, including backwards.</strong> The timestamp comes
 * from {@code System.currentTimeMillis()}, and when that steps back — an NTP correction, a
 * suspended VM resuming — the generator draws fresh entropy for the new (smaller) timestamp, so the
 * next ids sort <em>before</em> the ones just minted. That is worth stating rather than
 * discovering, but it is not worth preventing: what these ids are for is index locality, and a
 * momentary step back costs a little of it and nothing else. Nothing in this framework orders by an
 * id — the outbox orders by {@code created_at} and the table's own identity column, the process
 * manager by {@code seq}, and the deadline queries use an id only as a tie-break for a
 * deterministic result. Uniqueness does not depend on the clock either; it comes from the entropy,
 * which is redrawn on any timestamp change. An application that needs ids to be monotonic <em>as a
 * guarantee</em> needs a sequence, not a clock.
 */
public final class Uuidv7IdGenerator implements IdGenerator {

  private final TimeBasedEpochGenerator generator;

  public Uuidv7IdGenerator() {
    this(UUIDClock.systemTimeClock());
  }

  /**
   * Takes the clock, so a test can drive time instead of racing it. The properties worth asserting
   * here — a burst inside one millisecond stays ordered, a later millisecond sorts later, a
   * backwards step does what is documented above — are all statements about what happens at a given
   * instant, and against the real clock none of them can be stated without either flaking or
   * asserting something weaker than the truth.
   */
  Uuidv7IdGenerator(UUIDClock clock) {
    this.generator = Generators.timeBasedEpochGenerator(null, clock);
  }

  @Override
  public String newId() {
    return generator.generate().toString();
  }
}
