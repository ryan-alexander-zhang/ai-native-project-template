package com.example.inventory.application.stock;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.operationlog.annotation.OperationLog;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * Command to reserve stock for an order: the order id and the lines to reserve. No result.
 *
 * <p>This command arrives from an integration-event listener, not from HTTP, which is exactly why
 * its Bean Validation constraints matter: the command bus enforces them for every entry point, so
 * an inbound event with a malformed payload is rejected the same way a bad HTTP request would be —
 * there is no web adapter here to guard it.
 */
@OperationLog(
    code = "inventory.stock.reserve",
    targetType = "Order",
    targetId = "${input.orderId}",
    success = "Reserved stock for order ${input.orderId}",
    failure =
        "Stock reservation for order ${input.orderId} failed: ${failure.code} (${failure.safeSummary})")
public record ReserveStock(@NotBlank String orderId, @NotEmpty List<@Valid Line> lines)
    implements Command<Void> {

  public ReserveStock {
    // Defensive copy: keep this immutable command isolated from later mutation of the
    // caller's list. Null is left as-is so @NotEmpty still reports it as a validation error.
    lines = lines == null ? null : List.copyOf(lines);
  }

  public record Line(@NotBlank String sku, @Positive int quantity) {}
}
