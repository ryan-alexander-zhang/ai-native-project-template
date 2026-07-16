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
 */
@Component
@UseCase
public class ChargePaymentHandler implements CommandHandler<ChargePayment, Void> {

    private final AuthorizationPolicy authorization = new AuthorizationPolicy();
    private final IntegrationEvents integrationEvents;

    public ChargePaymentHandler(IntegrationEvents integrationEvents) {
        this.integrationEvents = integrationEvents;
    }

    @Override
    public Void handle(ChargePayment command, CommandContext context) {
        PaymentDecision decision = authorization.decide(command.amountMinor(), command.currency());
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
