package com.example.samples.s07.payments.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.Externalized;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * The fact that this service asked for money to be taken — and the durable intent that makes the
 * asking survive a crash.
 *
 * <p>This is the sample's least obvious move, so it is worth being precise about what it is and what it
 * is not.
 *
 * <p><strong>What it is.</strong> An integration event, written to the outbox in the same transaction
 * as the payment row, and later handed to a dispatcher. The library's outbox does not care that the
 * dispatcher on the other end speaks HTTP to a payment provider rather than Kafka to a topic: what it
 * provides — a row that commits with the aggregate, a lease so two instances cannot send it twice at
 * once, backoff between attempts, and a dead letter when attempts run out — is exactly the machinery an
 * outbound third-party call needs, and none of it is broker-specific.
 *
 * <p><strong>Why the destination looks like this.</strong> {@code gateway:charges} is not a topic. It is
 * a routing key that this application's own {@code EventDestinations} bean produces and its own
 * dispatcher understands; the library only requires that the destination be resolved when the row is
 * written and stored on it, so a row always remembers where it was going.
 *
 * <p><strong>What it is not.</strong> It is not a command dressed as an event, even though its only
 * subscriber is an API and the effect is imperative. The name stays in the past tense because that is
 * what the row records — we asked — and the reading of it as "so call {@code POST /charges}" lives
 * entirely in the dispatcher. Keeping that line clear is what allows a second destination to be added
 * later without renaming anything.
 *
 * <p><strong>What it costs.</strong> Three things, all in the README: an application gets one
 * {@code OutboxDispatcher}, so adding Kafka later means composing rather than installing; the relay
 * marks a row sent when dispatch returns, so it can never record the provider's answer; and "sent" is
 * therefore not "charged". The answer has to arrive by another road, which is the rest of this sample.
 */
@EventType(name = "com.example.samples.payments.ChargeRequested", version = 1, source = "/payments")
@Externalized("${payments.gateway.destination:gateway:charges}")
public record ChargeRequested(
    String paymentId, String orderRef, long amountMinor, String currency)
    implements IntegrationEvent {

  /**
   * The payment id, which is also the idempotency key sent to the provider.
   *
   * <p>On a broker this is the partition key; here it is what keeps one payment's rows in one queue.
   * The outbox claim admits at most one pending row per subject, so a payment can never have two
   * charge requests in flight at once — a property worth having when the far side moves money.
   */
  @Override
  public String subject() {
    return paymentId;
  }
}
