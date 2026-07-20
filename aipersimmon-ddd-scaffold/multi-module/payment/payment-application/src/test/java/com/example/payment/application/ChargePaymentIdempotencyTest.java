package com.example.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.example.payment.api.PaymentAuthorized;
import com.example.payment.api.PaymentDeclined;
import com.example.payment.domain.AuthorizationPolicy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The business-idempotency guarantee of {@link ChargePaymentHandler} (issue-00041): a charge is keyed
 * by its {@code paymentOperationId}, so an at-least-once redelivery of the same operation charges
 * once and announces one outcome event. Exercised as a pure unit test — a recording
 * {@link IntegrationEvents} and a real {@link InMemoryPaymentOperations}, no Spring context.
 */
class ChargePaymentIdempotencyTest {

    private final RecordingIntegrationEvents events = new RecordingIntegrationEvents();
    private final ChargePaymentHandler handler =
            new ChargePaymentHandler(events, new InMemoryPaymentOperations());

    private static final long UNDER_CEILING = AuthorizationPolicy.AUTHORISATION_CEILING_MINOR - 1;
    private static final long OVER_CEILING = AuthorizationPolicy.AUTHORISATION_CEILING_MINOR + 1;

    @Test
    void redeliveringTheSameOperationChargesOnceAndAnnouncesOneAuthorization() {
        ChargePayment charge = new ChargePayment("order-1", "op-1", UNDER_CEILING, "USD");

        handler.handle(charge, CommandContext.root("cmd-1"));
        handler.handle(charge, CommandContext.root("cmd-1")); // at-least-once redelivery

        assertEquals(1, events.published.size(), "a redelivered operation must announce exactly one outcome");
        assertInstanceOf(PaymentAuthorized.class, events.published.get(0));
    }

    @Test
    void redeliveringADeclinedOperationAnnouncesOneDeclineOnly() {
        ChargePayment charge = new ChargePayment("order-2", "op-2", OVER_CEILING, "USD");

        handler.handle(charge, CommandContext.root("cmd-2"));
        handler.handle(charge, CommandContext.root("cmd-2"));

        assertEquals(1, events.published.size());
        PaymentDeclined declined = assertInstanceOf(PaymentDeclined.class, events.published.get(0));
        assertEquals(AuthorizationPolicy.DECLINE_CODE, declined.code());
    }

    @Test
    void distinctOperationsAreEachCharged() {
        handler.handle(new ChargePayment("order-3", "op-3", UNDER_CEILING, "USD"), CommandContext.root("cmd-3"));
        handler.handle(new ChargePayment("order-4", "op-4", UNDER_CEILING, "USD"), CommandContext.root("cmd-4"));

        assertEquals(2, events.published.size(), "different operation ids are different charges");
    }

    private static final class RecordingIntegrationEvents implements IntegrationEvents {
        private final List<IntegrationEvent> published = new ArrayList<>();

        @Override
        public void publish(IntegrationEvent event, CommandContext context) {
            published.add(event);
        }
    }
}
