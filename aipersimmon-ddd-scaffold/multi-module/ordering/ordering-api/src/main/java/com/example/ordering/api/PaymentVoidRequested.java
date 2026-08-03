package com.example.ordering.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.Externalized;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * Integration event asking the payment context to void a payment operation — the ordering context's
 * cross-context contract for the payment compensation. The process manager emits it when it
 * abandons its wait for a payment answer: a timeout, or a customer cancellation racing the
 * authorization. The payment context settles the race atomically on its idempotency row for {@code
 * paymentOperationId}: an authorization already granted is voided (the hold released), one still in
 * flight finds the void first and is refused, one already declined needs nothing.
 *
 * <p>Nothing waits on an answer: the flow has already moved on, and voiding is idempotent under
 * at-least-once delivery — a redelivery finds the operation already voided. That is why, unlike
 * {@code PaymentRequested}, this contract has no outcome-event counterpart.
 *
 * <h2>A request, not a fact</h2>
 *
 * <p>Most of this topic's traffic announces things that already happened; this event <em>asks
 * another context to act</em>. It rides the same machinery on purpose — the outbox, the topic, the
 * ordering guarantee — but the consumption rule differs, and that is what this marking exists for:
 * a fact consumed twice is merely recorded twice, a request consumed twice does the work twice.
 * <strong>Any consumer acting on this event must deduplicate by {@code
 * paymentOperationId}</strong>, as the payment context does: the void claims or updates the
 * operation row keyed by it, and every shape of redelivery matches zero rows the second time.
 * Transport dedupe (the inbox) is a window, not a guarantee — see the retention reasoning in the
 * consuming application's configuration.
 */
@EventType(name = "com.example.ordering.PaymentVoidRequested", version = 1, source = "/ordering")
@Externalized("ordering.events")
public record PaymentVoidRequested(String orderId, String paymentOperationId)
    implements IntegrationEvent {

  public PaymentVoidRequested {
    // Both ids are the whole message: a void that names no operation voids nothing.
    Contract.required(orderId, "orderId");
    Contract.required(paymentOperationId, "paymentOperationId");
  }

  @Override
  public String subject() {
    return orderId();
  }
}
