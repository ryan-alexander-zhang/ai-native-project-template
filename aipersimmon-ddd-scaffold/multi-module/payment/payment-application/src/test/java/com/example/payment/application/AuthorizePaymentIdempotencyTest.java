package com.example.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.tenancy.Tenants;
import com.example.payment.api.PaymentAuthorized;
import com.example.payment.api.PaymentDeclined;
import com.example.payment.domain.CeilingAuthorizationPolicy;
import com.example.payment.domain.PaymentDecision;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * The business-idempotency guarantee of {@link AuthorizePaymentHandler} (issue-00041, issue-00069):
 * an authorization is keyed by its {@code paymentOperationId}, so an at-least-once redelivery
 * authorises <strong>once</strong> — and announces the outcome <strong>every time</strong>.
 *
 * <p>That second half is the correction. These tests used to assert one outcome event per
 * operation, pinning behaviour where a redelivery returned silently. Silence assumes the previous
 * outcome arrived, which is exactly what at-least-once delivery does not promise: if the first
 * announcement was lost, the flow waiting on it waits forever. The guarantee is "exactly one
 * authorization, at-least-once outcome delivery", and the reader is idempotent by construction —
 * {@code OrderFulfilmentDefinition} dispatches on {@code (step, input)} and ignores a duplicate
 * {@code PaymentAuthorized}.
 *
 * <p>A pure unit test: a recording {@link IntegrationEvents} and a local {@link PaymentOperations}
 * fake whose {@code record} rejects duplicates the way the table's primary key does. No Spring
 * context and no dependency on the infrastructure adapter.
 */
class AuthorizePaymentIdempotencyTest {

  private final RecordingIntegrationEvents events = new RecordingIntegrationEvents();
  private final FakePaymentOperations operations = new FakePaymentOperations();
  private final AuthorizePaymentHandler handler =
      new AuthorizePaymentHandler(new CeilingAuthorizationPolicy(), events, operations);

  private static final long UNDER_CEILING = CeilingAuthorizationPolicy.DEFAULT_CEILING_MINOR - 1;
  private static final long OVER_CEILING = CeilingAuthorizationPolicy.DEFAULT_CEILING_MINOR + 1;

  @Test
  void aRedeliveryAuthorisesOnceAndRepublishesTheRecordedOutcome() {
    AuthorizePayment authorize = new AuthorizePayment("order-1", "op-1", UNDER_CEILING, "USD");

    handler.handle(authorize, CommandContext.root(Tenants.ROOT, "cmd-1"));
    handler.handle(
        authorize, CommandContext.root(Tenants.ROOT, "cmd-1")); // at-least-once redelivery

    assertEquals(
        1,
        operations.records,
        "the irreversible act happens once — that is what the operation log is for");
    assertEquals(
        2,
        events.published.size(),
        "but the outcome is announced on every delivery: the first announcement may never have"
            + " arrived, and a silent redelivery would strand the flow waiting on it");
    assertInstanceOf(PaymentAuthorized.class, events.published.get(0));
    assertInstanceOf(PaymentAuthorized.class, events.published.get(1));
  }

  @Test
  void aRedeliveredDeclineRepublishesTheSameDecline() {
    AuthorizePayment authorize = new AuthorizePayment("order-2", "op-2", OVER_CEILING, "USD");

    handler.handle(authorize, CommandContext.root(Tenants.ROOT, "cmd-2"));
    handler.handle(authorize, CommandContext.root(Tenants.ROOT, "cmd-2"));

    assertEquals(1, operations.records);
    assertEquals(2, events.published.size());
    // The recorded decision is replayed rather than recomputed, so a rule or ceiling that changed
    // between deliveries cannot give one operation two different outcomes.
    PaymentDeclined first = assertInstanceOf(PaymentDeclined.class, events.published.get(0));
    PaymentDeclined replayed = assertInstanceOf(PaymentDeclined.class, events.published.get(1));
    assertEquals(CeilingAuthorizationPolicy.DECLINE_CODE, first.code());
    assertEquals(first.code(), replayed.code(), "the same decision, not a fresh one");
    assertEquals(first.reason(), replayed.reason());
  }

  @Test
  void distinctOperationsAreEachAuthorised() {
    handler.handle(
        new AuthorizePayment("order-3", "op-3", UNDER_CEILING, "USD"),
        CommandContext.root(Tenants.ROOT, "cmd-3"));
    handler.handle(
        new AuthorizePayment("order-4", "op-4", UNDER_CEILING, "USD"),
        CommandContext.root(Tenants.ROOT, "cmd-4"));

    assertEquals(2, operations.records, "different operation ids are different authorizations");
    assertEquals(2, events.published.size());
  }

  /**
   * Two first deliveries racing: both find nothing, both decide, and the loser's insert violates
   * the key. The handler does not catch that — it must roll the transaction back so the retry finds
   * the winner's decision and republishes it.
   */
  @Test
  void aLosingConcurrentClaimFailsRatherThanPublishingASecondDecision() {
    operations.record("op-race", new PaymentDecision.Authorized());
    operations.hideFromFind("op-race"); // simulate: the winner committed after this caller's find

    assertThrows(
        IllegalStateException.class,
        () ->
            handler.handle(
                new AuthorizePayment("order-5", "op-race", UNDER_CEILING, "USD"),
                CommandContext.root(Tenants.ROOT, "cmd-5")));
    assertEquals(0, events.published.size(), "the loser announces nothing");
  }

  /** Stands in for the table: {@code record} rejects a duplicate the way the primary key does. */
  private static final class FakePaymentOperations implements PaymentOperations {
    private final Map<String, PaymentDecision> byOperationId = new ConcurrentHashMap<>();
    private final List<String> hidden = new ArrayList<>();
    private int records;

    @Override
    public Optional<PaymentDecision> find(String operationId) {
      return hidden.contains(operationId)
          ? Optional.empty()
          : Optional.ofNullable(byOperationId.get(operationId));
    }

    @Override
    public void record(String operationId, PaymentDecision decision) {
      if (byOperationId.putIfAbsent(operationId, decision) != null) {
        throw new IllegalStateException("duplicate operation " + operationId);
      }
      records++;
    }

    void hideFromFind(String operationId) {
      hidden.add(operationId);
      records = 0;
    }
  }

  private static final class RecordingIntegrationEvents implements IntegrationEvents {
    private final List<IntegrationEvent> published = new ArrayList<>();

    @Override
    public void publish(IntegrationEvent event, CommandContext context) {
      published.add(event);
    }
  }
}
