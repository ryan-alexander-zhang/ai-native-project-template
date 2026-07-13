package com.example.inventory.application.stock;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.application.UseCase;
import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.inventory.api.StockReservationFailed;
import com.example.inventory.api.StockReserved;
import com.example.inventory.domain.stock.InventoryErrorCode;
import com.example.inventory.domain.stock.Sku;
import com.example.inventory.domain.stock.Stock;
import com.example.inventory.domain.stock.Stocks;
import org.springframework.stereotype.Component;

/**
 * Handles {@link ReserveStock} and announces the outcome: a {@link StockReserved}
 * event on success, or a {@link StockReservationFailed} event if any line cannot be
 * reserved. Reporting failure as an event (rather than throwing) lets the ordering
 * context's saga react to it and compensate. It validates every line before
 * reserving any, so a failure leaves no partial reservation.
 */
@Component
@UseCase
public class ReserveStockHandler implements CommandHandler<ReserveStock, Void> {

    private final Stocks stocks;
    private final IntegrationEvents integrationEvents;

    public ReserveStockHandler(Stocks stocks, IntegrationEvents integrationEvents) {
        this.stocks = stocks;
        this.integrationEvents = integrationEvents;
    }

    @Override
    public Void handle(ReserveStock command) {
        try {
            // Validate all lines first, so a later failure leaves no partial reservation.
            for (ReserveStock.Line line : command.lines()) {
                Stock stock = stockFor(line.sku());
                if (line.quantity() <= 0 || line.quantity() > stock.available()) {
                    throw new DomainException(InventoryErrorCode.INSUFFICIENT_STOCK,
                            "cannot reserve " + line.quantity() + " of " + line.sku());
                }
            }
            for (ReserveStock.Line line : command.lines()) {
                Stock stock = stockFor(line.sku());
                stock.reserve(line.quantity());
                stocks.save(stock);
            }
            integrationEvents.publish(new StockReserved(command.orderId()));
        } catch (DomainException failure) {
            // The failing code (if any) rides the event: a BC with no HTTP edge still
            // surfaces a stable machine identity for the reacting saga to branch on.
            String code = failure.errorCode().map(ErrorCode::code).orElse(null);
            integrationEvents.publish(
                    new StockReservationFailed(command.orderId(), code, failure.getMessage()));
        }
        return null;
    }

    private Stock stockFor(String sku) {
        return stocks.findBySku(new Sku(sku))
                .orElseThrow(() -> new DomainException(
                        InventoryErrorCode.STOCK_NOT_FOUND, "unknown sku: " + sku));
    }
}
