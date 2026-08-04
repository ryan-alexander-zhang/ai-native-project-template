package com.example.samples.s07.payments.application;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.core.id.IdGenerator;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s07.payments.api.ChargeRequested;
import com.example.samples.s07.payments.domain.Payment;
import com.example.samples.s07.payments.domain.PaymentId;
import com.example.samples.s07.payments.domain.Payments;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Two writes, one transaction, and — the point of the whole sample — <strong>no HTTP call</strong>.
 *
 * <p>The tempting version of this handler ends with {@code gateway.charge(...)}. It is wrong in three
 * separate ways, and each one is a production incident with a name:
 *
 * <ol>
 *   <li><strong>The transaction is open while the network waits.</strong> A database connection is
 *       pinned for the duration of a call to a system whose latency is somebody else's operational
 *       decision. One slow morning at the provider drains the pool and takes down endpoints that have
 *       nothing to do with payments. (S6 puts a synchronous call in a precheck for exactly this
 *       reason; here it goes further out still, to after the commit.)
 *   <li><strong>The call cannot be rolled back.</strong> If anything after it fails, the transaction
 *       reverses the row and not the charge, and the money is gone with no local record of it.
 *   <li><strong>The customer waits for the provider.</strong> The answer is asynchronous anyway, so
 *       the wait buys nothing but a longer window in which to time out.
 *   </ol>
 *
 * <p>So the handler records the intent and hands the sending to something that can retry. The row and
 * the outbox row commit together: after this method returns there is no state in which we have promised
 * to charge and forgotten to, and none in which we charge without a record.
 *
 * <p>It also mints the id before publishing, because that id <em>is</em> the idempotency key the
 * provider will dedupe on. An id assigned by the database on insert would not be available to the
 * payload, and a key minted at send time would be different on every retry — which is the same as
 * having none.
 */
@Component
class RequestPaymentHandler implements CommandHandler<RequestPayment, String> {

  private final Payments payments;
  private final IntegrationEvents integrationEvents;
  private final IdGenerator idGenerator;
  private final Clock clock;
  private final String currency;

  RequestPaymentHandler(
      Payments payments,
      IntegrationEvents integrationEvents,
      IdGenerator idGenerator,
      Clock clock,
      @Value("${payments.gateway.currency:EUR}") String currency) {
    this.payments = payments;
    this.integrationEvents = integrationEvents;
    this.idGenerator = idGenerator;
    this.clock = clock;
    this.currency = currency;
  }

  @Override
  public String handle(RequestPayment command, CommandContext context) {
    PaymentId id = new PaymentId(idGenerator.newId());
    payments.save(Payment.request(id, command.orderRef(), command.amountMinor(), clock.instant()));

    // Currency comes from configuration rather than the aggregate: this service charges in one, and
    // pretending otherwise would mean a field nothing ever varies. A multi-currency deployment puts it
    // on the payment, where it belongs, and the provider's contract is the same either way.
    integrationEvents.publish(
        new ChargeRequested(id.value(), command.orderRef(), command.amountMinor(), currency),
        context);
    return id.value();
  }
}
