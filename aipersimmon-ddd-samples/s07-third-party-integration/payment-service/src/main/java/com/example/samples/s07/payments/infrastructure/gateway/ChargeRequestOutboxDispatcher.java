package com.example.samples.s07.payments.infrastructure.gateway;

import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import com.example.samples.s07.payments.api.ChargeRequested;
import com.example.samples.s07.payments.infrastructure.gateway.GatewayMessages.ChargeAccepted;
import com.example.samples.s07.payments.infrastructure.gateway.GatewayMessages.ChargeRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * The outbound half of the anticorruption layer: a transport for the library's outbox that happens to
 * speak a payment provider's REST API.
 *
 * <p><strong>Two legs, and the reason the local one is not optional.</strong> An application gets exactly
 * one {@code OutboxDispatcher} — the relay injects one, and every default in
 * {@code AipersimmonDddOutboxAutoConfiguration} backs off with {@code @ConditionalOnMissingBean}. So
 * defining this bean removes the in-process republisher that would otherwise deliver LOCAL events (the
 * ones with no {@code @Externalized}) to their {@code @EventListener}s. Nothing warns about it: the relay
 * marks a row sent whenever {@code dispatch} returns normally, so events with no destination would be
 * quietly archived as delivered. Composing the leg back in is what the library's own javadoc instructs —
 * "to deliver an event more than one way (fan-out) or route by type, define your own
 * {@code OutboxDispatcher} bean that composes the others" — and a test in this module publishes a local
 * event to prove the leg is really wired.
 *
 * <p><strong>What this class may not do: record the answer.</strong> The provider's 202 carries a
 * {@code txn_ref}, and it is logged and dropped. Writing it to the payment would put an aggregate
 * transition in a transport adapter — outside any command, so outside validation, the interceptor chain
 * and the operation log — and it would do so in a place the relay is about to mark sent regardless of
 * whether that write succeeded. The relay is a courier. The answer arrives by callback or by the pull
 * channel, and both of those come in through a command.
 *
 * <p><strong>Therefore the provider's contract had to be chosen to suit.</strong> This gateway answers
 * 202 and nothing of business consequence, so nothing is lost by dropping it. A provider that returns the
 * <em>decision</em> synchronously — many do — is a poor fit for this pipe: routing it through the outbox
 * means ignoring an answer you already have and waiting for the webhook, which costs latency and means a
 * declined card is learned a round trip later. That is a real trade, and the alternative is a dedicated
 * task table drained by a worker that can write both the attempt and its answer. What the outbox buys in
 * exchange is a row that commits with the aggregate, a lease so two instances cannot send it at once,
 * backoff, and a dead letter — none of which has to be written or tested.
 *
 * <p><strong>At-least-once, so the key does the work.</strong> A dispatch that throws leaves the row
 * unsent and it is retried on a later poll; a dispatch that succeeded but whose {@code markSent} failed is
 * re-dispatched too (the library says so and logs it). Both mean the provider can receive the same charge
 * twice, and the only thing standing between that and a double debit is the {@code Idempotency-Key} header
 * below. It carries the payment id, which existed before the first attempt and is identical on every one.
 */
class ChargeRequestOutboxDispatcher implements OutboxDispatcher {

  private static final Logger log = LoggerFactory.getLogger(ChargeRequestOutboxDispatcher.class);

  private static final String CHARGE_REQUESTED_TYPE =
      IntegrationEvent.eventTypeOf(ChargeRequested.class);

  private final OutboxDispatcher inProcessLeg;
  private final RestClient gateway;
  private final ObjectMapper objectMapper;
  private final String gatewayDestination;

  ChargeRequestOutboxDispatcher(
      OutboxDispatcher inProcessLeg,
      RestClient gateway,
      ObjectMapper objectMapper,
      String gatewayDestination) {
    this.inProcessLeg = inProcessLeg;
    this.gateway = gateway;
    this.objectMapper = objectMapper;
    this.gatewayDestination = gatewayDestination;
  }

  /**
   * True, and it has to be checked rather than assumed: the outbox refuses to start an application whose
   * {@code @Externalized} events have no way out, and it decides that by asking the active dispatcher
   * this question. Answering it wrongly in either direction is a startup failure or silent loss.
   */
  @Override
  public boolean reachesExternalTargets() {
    return true;
  }

  @Override
  public void dispatch(OutboxMessage message) {
    if (!gatewayDestination.equals(message.destination())) {
      // No destination, or one this transport does not own: hand it to the in-process leg, which is the
      // correct delivery for a LOCAL event and the reason this class composes rather than replaces.
      inProcessLeg.dispatch(message);
      return;
    }
    if (!CHARGE_REQUESTED_TYPE.equals(message.type())) {
      // A row routed here whose type this adapter does not send. Better a loud failure than a request
      // built out of fields that happened to deserialize.
      throw new IllegalStateException(
          "outbox row of type '"
              + message.type()
              + "' was routed to "
              + gatewayDestination
              + ", which only carries "
              + CHARGE_REQUESTED_TYPE);
    }
    send(read(message));
  }

  private ChargeRequested read(OutboxMessage message) {
    try {
      return objectMapper.readValue(message.payload(), ChargeRequested.class);
    } catch (JsonProcessingException malformed) {
      // Deliberately not wrapped in something generic: the library's DefaultFailureClassifier walks the
      // cause chain for a JsonProcessingException and treats it as PERMANENT, so a payload that will not
      // parse now dead-letters instead of being retried ten times over the next hour.
      throw new IllegalStateException(
          "outbox row " + message.eventId() + " does not hold a ChargeRequested payload", malformed);
    }
  }

  private void send(ChargeRequested charge) {
    ChargeAccepted accepted =
        gateway
            .post()
            .uri("/charges")
            // The whole safety argument of the outbound path, in one header.
            .header("Idempotency-Key", charge.paymentId())
            .body(
                new ChargeRequest(charge.paymentId(), charge.amountMinor(), charge.currency()))
            .retrieve()
            .body(ChargeAccepted.class);

    // Logged, not stored. See the class javadoc: a courier does not amend the parcel.
    log.info(
        "charge requested for payment {} (order {}), provider acknowledged as {}",
        charge.paymentId(),
        charge.orderRef(),
        accepted == null ? "<no reference>" : accepted.txnRef());
  }
}
