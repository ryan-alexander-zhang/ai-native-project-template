package com.example.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.tenancy.Tenants;
import com.aipersimmon.ddd.test.RecordingIntegrationEvents;
import com.example.payment.api.PaymentDeclined;
import com.example.payment.domain.CeilingAuthorizationPolicy;
import com.example.payment.domain.PaymentDecision;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The void/authorize race, settled on the operation row. Ordering abandons its wait for an
 * operation — a timeout, or a cancellation racing the authorization — and sends a void; the two
 * requests may arrive here in either order, and every interleaving must leave the operation without
 * a live hold:
 *
 * <ul>
 *   <li>void first — a refusal in advance: the later authorization must not authorize, and must
 *       still answer (a decline), because the outcome contract is per-delivery even when the asker
 *       has moved on;
 *   <li>authorize first — the case the issue names: an authorization hold exists for an order that
 *       will be cancelled, and the void releases it;
 *   <li>void after a decline, or a redelivered void — nothing is held; both fall through, which is
 *       what lets ordering fire-and-forget the void over an at-least-once relay.
 * </ul>
 */
class PaymentVoidRaceTest {

  private static final long UNDER_CEILING = CeilingAuthorizationPolicy.DEFAULT_CEILING_MINOR - 1;
  private static final long OVER_CEILING = CeilingAuthorizationPolicy.DEFAULT_CEILING_MINOR + 1;

  private final RecordingIntegrationEvents events = new RecordingIntegrationEvents();
  private final FakePaymentOperations operations = new FakePaymentOperations();
  private final AuthorizePaymentHandler authorize =
      new AuthorizePaymentHandler(new CeilingAuthorizationPolicy(), events, operations);
  private final VoidPaymentHandler voidPayment = new VoidPaymentHandler(operations);

  @Test
  void aVoidArrivingFirstRefusesTheLaterAuthorizationInAdvance() {
    voidPayment.handle(new VoidPayment("order-1", "op-1"), context("cmd-void"));
    authorize.handle(
        new AuthorizePayment("order-1", "op-1", UNDER_CEILING, "USD"), context("cmd-auth"));

    assertInstanceOf(
        PaymentDecision.Voided.class,
        operations.decisions.get("op-1"),
        "the void won the row; the authorization must not overwrite it");
    PaymentDeclined declined = assertInstanceOf(PaymentDeclined.class, events.events().get(0));
    assertEquals(
        AuthorizePaymentHandler.VOIDED_CODE,
        declined.code(),
        "the authorization still answers — with a decline, not with silence and not with a hold");
  }

  @Test
  void aVoidArrivingAfterTheAuthorizationReleasesTheHold() {
    authorize.handle(
        new AuthorizePayment("order-2", "op-2", UNDER_CEILING, "USD"), context("cmd-auth"));
    assertInstanceOf(PaymentDecision.Authorized.class, operations.decisions.get("op-2"));

    voidPayment.handle(new VoidPayment("order-2", "op-2"), context("cmd-void"));

    assertInstanceOf(
        PaymentDecision.Voided.class,
        operations.decisions.get("op-2"),
        "this is the orphaned hold the issue names — the void must release it");
  }

  @Test
  void aRedeliveredVoidChangesNothing() {
    voidPayment.handle(new VoidPayment("order-3", "op-3"), context("cmd-void-1"));
    // At-least-once redelivery: the second void finds the recorded Voided and must not try to
    // claim the row again (the claim would raise the duplicate that resolves genuine races).
    voidPayment.handle(new VoidPayment("order-3", "op-3"), context("cmd-void-2"));

    assertInstanceOf(PaymentDecision.Voided.class, operations.decisions.get("op-3"));
  }

  @Test
  void aVoidAfterADeclineFallsThrough() {
    authorize.handle(
        new AuthorizePayment("order-4", "op-4", OVER_CEILING, "USD"), context("cmd-auth"));
    PaymentDecision declined = operations.decisions.get("op-4");
    assertInstanceOf(PaymentDecision.Declined.class, declined);

    voidPayment.handle(new VoidPayment("order-4", "op-4"), context("cmd-void"));

    assertEquals(
        declined,
        operations.decisions.get("op-4"),
        "nothing was held, so there is nothing to void — the recorded refusal stands");
  }

  private static CommandContext context(String messageId) {
    return CommandContext.root(Tenants.ROOT, messageId);
  }

  /** In-memory stand-in for the operation row; the map's atomicity plays the primary key. */
  private static final class FakePaymentOperations implements PaymentOperations {
    final Map<String, PaymentDecision> decisions = new HashMap<>();

    @Override
    public Optional<PaymentDecision> find(String operationId) {
      return Optional.ofNullable(decisions.get(operationId));
    }

    @Override
    public void record(String operationId, PaymentDecision decision) {
      if (decisions.putIfAbsent(operationId, decision) != null) {
        throw new IllegalStateException("duplicate operation " + operationId);
      }
    }

    @Override
    public void markVoided(String operationId) {
      decisions.computeIfPresent(
          operationId,
          (id, decision) ->
              decision instanceof PaymentDecision.Authorized
                  ? new PaymentDecision.Voided()
                  : decision);
    }
  }
}
