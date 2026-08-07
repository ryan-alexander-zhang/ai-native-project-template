package com.aipersimmon.ddd.archunit.fixture.bad.ordering.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.UUID;

/**
 * Violates {@code domainShouldNotUseAmbientTimeOrRandomness} in every shape the condition
 * recognises: a {@code java.time} {@code now()} with no argument, one with a {@code ZoneId} (still
 * the system clock), a named static entry point, and a no-argument constructor that captures the
 * current time.
 */
public class BadSelfStampingOrder {

  private final String id = UUID.randomUUID().toString();
  private final Instant placedAt = Instant.now();

  public LocalDate placedOn() {
    return LocalDate.now(ZoneId.of("UTC"));
  }

  public long millis() {
    return System.currentTimeMillis();
  }

  public Date legacyStamp() {
    return new Date();
  }

  public String id() {
    return id;
  }

  public Instant placedAt() {
    return placedAt;
  }
}
