package com.example.samples.s25.refunds.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * A refund was raised. The new context's first published fact — and note which identity it carries.
 *
 * <p>{@code refundId} is the {@code public_id} UUID, never the {@code bigint}. Publishing the number would put a
 * database counter into a contract that outlives the migration: it leaks volume, it is guessable, and it cannot
 * survive two deployments being merged. The number is an internal detail of a table that is on its way out; the UUID
 * is the thing consumers will still be holding afterwards.
 *
 * <p>{@code orderId} <em>is</em> the legacy number, because the order has not been extracted and its identity is
 * genuinely that number today. Which is worth noticing as the honest shape of a migration in progress: a published
 * event can carry a new identity for the part that moved and the old one for the part that has not, and pretending
 * otherwise would mean minting an identity for an aggregate nobody has modelled yet.
 */
@EventType(name = "com.example.samples.refunds.RefundRaised", version = 1, source = "/refunds")
public record RefundRaised(String refundId, long orderId, long amountCents)
    implements IntegrationEvent {

  @Override
  public String subject() {
    return refundId;
  }
}
