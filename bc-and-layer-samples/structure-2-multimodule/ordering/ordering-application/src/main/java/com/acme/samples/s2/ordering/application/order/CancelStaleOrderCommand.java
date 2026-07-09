package com.acme.samples.s2.ordering.application.order;

import com.acme.samples.s2.shared.Command;
import jakarta.validation.constraints.NotBlank;

/**
 * Command: compensate a stale PENDING order whose stock result never arrived
 * within the deadline (analysis-00005 §八/G6). Dispatched by the timeout scanner.
 */
public record CancelStaleOrderCommand(@NotBlank String orderId) implements Command<Void> {
}
