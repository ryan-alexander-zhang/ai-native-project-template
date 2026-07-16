package com.example.ordering.application.order;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.application.UseCase;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.ordering.api.StockReleaseRequested;
import org.springframework.stereotype.Component;

/**
 * Handles {@link RequestStockRelease}: publishes {@link StockReleaseRequested} so the inventory
 * context can release the reservation. Keeps the outbound-event concern in a use-case handler, so
 * the process manager sends only ordering commands.
 */
@Component
@UseCase
public class RequestStockReleaseHandler implements CommandHandler<RequestStockRelease, Void> {

    private final IntegrationEvents integrationEvents;

    public RequestStockReleaseHandler(IntegrationEvents integrationEvents) {
        this.integrationEvents = integrationEvents;
    }

    @Override
    public Void handle(RequestStockRelease command, CommandContext context) {
        integrationEvents.publish(
                new StockReleaseRequested(command.orderId(), command.reservationId()), context);
        return null;
    }
}
