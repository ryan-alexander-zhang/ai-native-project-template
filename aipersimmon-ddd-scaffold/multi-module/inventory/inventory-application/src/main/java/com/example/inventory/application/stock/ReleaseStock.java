package com.example.inventory.application.stock;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.operationlog.annotation.OperationLog;
import jakarta.validation.constraints.NotBlank;

/**
 * Command to release a previously made reservation (the compensation for {@link ReserveStock}),
 * addressed by its {@code reservationId}. Releasing by the reservation handle — not by re-listing
 * the SKUs — is what lets inventory hand back exactly what it held, and only once. No result.
 */
@OperationLog(
    code = "inventory.stock.release",
    targetType = "StockReservation",
    targetId = "${input.reservationId}",
    success = "Released stock reservation ${input.reservationId}",
    failure =
        "Stock release for reservation ${input.reservationId} failed: ${failure.code} (${failure.safeSummary})")
public record ReleaseStock(@NotBlank String reservationId) implements Command<Void> {}
