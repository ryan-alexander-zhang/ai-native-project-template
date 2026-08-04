package com.example.samples.s24.ordering.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * An order was placed. Ordering's published fact, and the asynchronous half of the boundary.
 *
 * <p>What it carries is the whole design decision. Ids and amounts — no lines, no customer details, no status enum.
 * A consumer that needed the lines would be a consumer doing ordering's job, and every field added here is a field that
 * can no longer change without a conversation.
 *
 * <p>{@code couponCode} and {@code discountMinor} are here because the redemption cannot be worked out without them, and
 * that is the test to apply to a published event: <strong>does a consumer need it to do its own job, or would it be
 * doing mine?</strong>
 *
 * <p>An {@code IntegrationEvent} rather than a domain event, even though both contexts are in one process today. A
 * domain event lives in {@code domain} and is private to it, so a consumer in another context could not subscribe to one
 * without reaching inside. The transport happening to be in-process is a deployment fact, not a contract; this type is
 * the contract, and it does not change when the transport does.
 */
@EventType(name = "com.example.samples.ordering.OrderPlaced", version = 1, source = "/orders")
public record OrderPlaced(
    String orderId,
    String customerId,
    long grossMinor,
    long discountMinor,
    String currency,
    String couponCode)
    implements IntegrationEvent {

  @Override
  public String subject() {
    return orderId;
  }
}
