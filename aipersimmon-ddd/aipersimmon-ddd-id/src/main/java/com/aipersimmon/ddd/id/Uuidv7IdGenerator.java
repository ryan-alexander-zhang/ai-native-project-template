package com.aipersimmon.ddd.id;

import com.aipersimmon.ddd.core.id.IdGenerator;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;

/**
 * The default {@link IdGenerator}: a time-ordered UUIDv7 (RFC 9562) rendered as a 36-char string, a
 * drop-in for {@code UUID.randomUUID().toString()} that inserts near the tail of an index instead
 * of scattering random writes.
 *
 * <p>Backed by JUG's {@link Generators#timeBasedEpochGenerator()} — the <em>monotonic</em> variant:
 * within a single millisecond it increments the random component of the previous value rather than
 * drawing a fresh one, so a burst of ids stays strictly increasing and keeps its index locality.
 * The underlying generator guards its state with a lock, so a single shared instance is safe to
 * call from many threads; this class holds exactly one.
 */
public final class Uuidv7IdGenerator implements IdGenerator {

  private final TimeBasedEpochGenerator generator = Generators.timeBasedEpochGenerator();

  @Override
  public String newId() {
    return generator.generate().toString();
  }
}
