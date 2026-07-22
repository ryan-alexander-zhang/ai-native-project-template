package com.example.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.example.payment.api.PaymentAuthorized;
import com.example.payment.api.PaymentDeclined;
import com.example.payment.domain.AuthorizationPolicy;
import com.example.payment.domain.PaymentDecision;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * The business-idempotency guarantee of {@link AuthorizePaymentHandler} (issue-00041): an
 * authorization is keyed by its {@code paymentOperationId}, so an at-least-once redelivery of the
 * same operation authorises once and announces one outcome event. Exercised as a pure unit test — a
 * recording {@link IntegrationEvents} and a local {@link PaymentOperations} fake, no Spring context
 * and no dependency on the infrastructure adapter.
 */
class AuthorizePaymentIdempotencyTest {

  private final RecordingIntegrationEvents events = new RecordingIntegrationEvents();
  private final AuthorizePaymentHandler handler =
      new AuthorizePaymentHandler(events, new FakePaymentOperations());

  private static final long UNDER_CEILING = AuthorizationPolicy.AUTHORISATION_CEILING_MINOR - 1;
  private static final long OVER_CEILING = AuthorizationPolicy.AUTHORISATION_CEILING_MINOR + 1;

  @Test
  void redeliveringTheSameOperationAuthorisesOnceAndAnnouncesOneAuthorization() {
    AuthorizePayment authorize = new AuthorizePayment("order-1", "op-1", UNDER_CEILING, "USD");

    handler.handle(authorize, CommandContext.root("cmd-1"));
    handler.handle(authorize, CommandContext.root("cmd-1")); // at-least-once redelivery

    assertEquals(
        1, events.published.size(), "a redelivered operation must announce exactly one outcome");
    assertInstanceOf(PaymentAuthorized.class, events.published.get(0));
  }

  @Test
  void redeliveringADeclinedOperationAnnouncesOneDeclineOnly() {
    AuthorizePayment authorize = new AuthorizePayment("order-2", "op-2", OVER_CEILING, "USD");

    handler.handle(authorize, CommandContext.root("cmd-2"));
    handler.handle(authorize, CommandContext.root("cmd-2"));

    assertEquals(1, events.published.size());
    PaymentDeclined declined = assertInstanceOf(PaymentDeclined.class, events.published.get(0));
    assertEquals(AuthorizationPolicy.DECLINE_CODE, declined.code());
  }

  @Test
  void distinctOperationsAreEachAuthorised() {
    handler.handle(
        new AuthorizePayment("order-3", "op-3", UNDER_CEILING, "USD"),
        CommandContext.root("cmd-3"));
    handler.handle(
        new AuthorizePayment("order-4", "op-4", UNDER_CEILING, "USD"),
        CommandContext.root("cmd-4"));

    assertEquals(
        2, events.published.size(), "different operation ids are different authorizations");
  }

  /** A minimal at-most-once operation log, standing in for the real infrastructure adapter. */
  private static final class FakePaymentOperations implements PaymentOperations {
    private final Map<String, PaymentDecision> byOperationId = new ConcurrentHashMap<>();

    @Override
    public boolean recordIfFirst(String operationId, PaymentDecision decision) {
      return byOperationId.putIfAbsent(operationId, decision) == null;
    }

    @Override
    public Optional<PaymentDecision> find(String operationId) {
      return Optional.ofNullable(byOperationId.get(operationId));
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
