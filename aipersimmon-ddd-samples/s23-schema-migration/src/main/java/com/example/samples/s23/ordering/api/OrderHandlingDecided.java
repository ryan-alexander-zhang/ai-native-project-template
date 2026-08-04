package com.example.samples.s23.ordering.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * An order's handling has been decided — and this event is the reason the backfill is a command rather than
 * an UPDATE statement.
 *
 * <p>The rows V4 touches are years old. Deciding their handling changes what downstream should believe about
 * them, and a database-only backfill leaves every consumer's copy stale with nothing to tell them so: no
 * event, no version bump, no way to notice. The fact that this event exists at all is the second half of the
 * criterion in the README — a backfill that must be announced cannot be SQL, because SQL has nobody to tell.
 *
 * <p>It carries the decision and not the reasoning. A consumer that needs to know <em>why</em> an order is
 * expedited is asking for the rule, and the rule is not a published contract.
 */
@EventType(
    name = "com.example.samples.ordering.OrderHandlingDecided",
    version = 1,
    source = "/ordering")
public record OrderHandlingDecided(String orderId, String handling) implements IntegrationEvent {

  @Override
  public String subject() {
    return orderId;
  }
}
