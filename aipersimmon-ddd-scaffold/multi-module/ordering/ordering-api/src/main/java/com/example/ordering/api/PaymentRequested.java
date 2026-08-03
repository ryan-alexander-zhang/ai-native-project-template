package com.example.ordering.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.Externalized;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * Integration event asking the payment context to authorize payment for an order — the ordering
 * context's cross-context contract for the payment step of fulfilment. The process manager emits it
 * once stock is reserved; the payment context reacts and answers with its own {@code
 * PaymentAuthorized} or {@code PaymentDeclined}. It carries the amount to authorize in minor units
 * plus its currency, and a {@code paymentOperationId} — the business idempotency key the payment
 * context dedupes by, so an at-least-once redelivery of this event authorizes only once.
 *
 * <p><strong>Range of {@code amountMinor}: zero or greater.</strong> Zero is a legal amount — a
 * gift line or a fully discounted basket totals zero, and ordering accepts one (the place-order
 * line is {@code @PositiveOrZero}), so this contract carries it and a consumer must handle it. This
 * sentence is the whole point of the fix it records: the two sides each declared a range in their
 * own validation annotations, ordering's wider than payment's, and nothing connected them — a
 * zero-amount order was accepted, its authorization was rejected as a constraint violation, and the
 * order was cancelled two minutes later as a payment timeout. The published language is where a
 * range like this becomes one agreement instead of two guesses.
 *
 * <h2>A request, not a fact</h2>
 *
 * <p>Most of this topic's traffic announces things that already happened; this event <em>asks
 * another context to act</em>. It rides the same machinery on purpose — the outbox, the topic, the
 * ordering guarantee — but the consumption rule differs, and that is what this marking exists for:
 * a fact consumed twice is merely recorded twice, a request consumed twice does the work twice.
 * <strong>Any consumer acting on this event must deduplicate by {@code
 * paymentOperationId}</strong>, as the payment context does: its operations row is the business
 * idempotency log, and a redelivery finds the recorded decision and republishes it instead of
 * authorizing again. Transport dedupe (the inbox) is a window, not a guarantee — see the retention
 * reasoning in the consuming application's configuration.
 */
@EventType(name = "com.example.ordering.PaymentRequested", version = 1, source = "/ordering")
@Externalized("ordering.events")
public record PaymentRequested(
    String orderId, String paymentOperationId, long amountMinor, String currency)
    implements IntegrationEvent {

  public PaymentRequested {
    // The range the javadoc above agrees on, as code: zero is a legal amount,
    // negative is not an amount at all. The ids and currency are what payment dedupes and
    // authorizes by — a payload without them cannot be acted on and is refused at parse time.
    Contract.required(orderId, "orderId");
    Contract.required(paymentOperationId, "paymentOperationId");
    Contract.required(currency, "currency");
    if (amountMinor < 0) {
      throw new IllegalArgumentException("amountMinor must be >= 0, got " + amountMinor);
    }
  }

  @Override
  public String subject() {
    return orderId();
  }
}
