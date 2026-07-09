package com.acme.samples.s2.ordering.application.order;

import com.acme.samples.s2.shared.Command;
import jakarta.validation.constraints.NotBlank;

/**
 * Command: apply Inventory's stock decision to an order (confirm/cancel). Triggered
 * from the {@code stock-result} integration event; idempotency is handled by the
 * inbox inside the handler (analysis-00005 §5.1 / G4). Returns nothing.
 */
public record ConfirmOrderCommand(@NotBlank String orderId, boolean reserved) implements Command<Void> {
}
