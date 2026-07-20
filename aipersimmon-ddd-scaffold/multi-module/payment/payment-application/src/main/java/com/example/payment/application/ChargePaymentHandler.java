package com.example.payment.application;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.application.UseCase;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.payment.api.PaymentAuthorized;
import com.example.payment.api.PaymentDeclined;
import com.example.payment.domain.AuthorizationPolicy;
import com.example.payment.domain.PaymentDecision;
import org.springframework.stereotype.Component;

/**
 * Handles {@link ChargePayment}: applies the domain {@link AuthorizationPolicy} and announces the
 * outcome — {@link PaymentAuthorized} or {@link PaymentDeclined}. Reporting the outcome as an event
 * (rather than a return value or a throw) is what lets the ordering saga treat authorisation and
 * decline as the two branches of the fulfilment flow.
 *
 * <p>Charging is an irreversible action, so it is guarded by the {@code paymentOperationId} business
 * idempotency key rather than trusting transport-level dedupe alone (design-00004 §13.2). The handler
 * claims the operation in {@link PaymentOperations} before it charges: the first delivery of an
 * operation wins the claim, decides, and announces the outcome; any redelivery of the same operation
 * loses the claim and returns without charging or re-announcing — so an at-least-once redelivery
 * produces exactly one charge and one outcome event.
 */
@Component
@UseCase
public class ChargePaymentHandler implements CommandHandler<ChargePayment, Void> {

    private final AuthorizationPolicy authorization = new AuthorizationPolicy();
    private final IntegrationEvents integrationEvents;
    private final PaymentOperations operations;

    public ChargePaymentHandler(IntegrationEvents integrationEvents, PaymentOperations operations) {
        this.integrationEvents = integrationEvents;
        this.operations = operations;
    }

    @Override
    public Void handle(ChargePayment command, CommandContext context) {
        PaymentDecision decision = authorization.decide(command.amountMinor(), command.currency());
        if (!operations.recordIfFirst(command.paymentOperationId(), decision)) {
            // A redelivery of an already-charged operation: idempotent no-op, do not charge again.
            return null;
        }
        switch (decision) {
            case PaymentDecision.Authorized ignored ->
                    integrationEvents.publish(new PaymentAuthorized(command.orderId()), context);
            case PaymentDecision.Declined declined ->
                    integrationEvents.publish(
                            new PaymentDeclined(command.orderId(), declined.code(), declined.reason()), context);
        }
        return null;
    }
}
