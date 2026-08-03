package com.aipersimmon.ddd.outbox.mybatisplus;

import java.time.Instant;

/**
 * The aggregate columns of the backlog query. A plain bean rather than the engine's {@code
 * PendingBacklog} record because MyBatis maps a result set onto setters; the store converts.
 */
public class PendingBacklogRow {

  private long pending;
  private Instant oldest;
  private long givenUp;

  public long getPending() {
    return pending;
  }

  public void setPending(long pending) {
    this.pending = pending;
  }

  public Instant getOldest() {
    return oldest;
  }

  public void setOldest(Instant oldest) {
    this.oldest = oldest;
  }

  public long getGivenUp() {
    return givenUp;
  }

  public void setGivenUp(long givenUp) {
    this.givenUp = givenUp;
  }
}
