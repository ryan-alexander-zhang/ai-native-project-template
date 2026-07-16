package com.example.inventory.application.stock;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;

/**
 * Command to release a previously made reservation (the compensation for {@link ReserveStock}),
 * addressed by its {@code reservationId}. Releasing by the reservation handle — not by re-listing
 * the SKUs — is what lets inventory hand back exactly what it held, and only once. No result.
 */
public record ReleaseStock(@NotBlank String reservationId) implements Command<Void> {
}
