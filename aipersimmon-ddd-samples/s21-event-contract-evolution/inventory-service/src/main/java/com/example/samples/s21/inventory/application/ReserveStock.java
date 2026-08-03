package com.example.samples.s21.inventory.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * Reserve stock for an order, in a named warehouse.
 *
 * <p>{@code warehouse} is {@code @NotBlank} while the contract's {@code warehouseCode} is nullable, and
 * the gap between those two facts is the inbound adapter's whole job: outside, absence is a legitimate
 * state of an old revision; inside, it is not a state this use case has to handle. Resolving it at the
 * boundary is what keeps the revision history out of the domain.
 *
 * <p>No message id, no source: the framework's consumer bridge has already deduplicated by
 * {@code (ce_source, ce_id)} before this command is issued. S4 covers why checking again silently skips
 * every message.
 */
public record ReserveStock(
    @NotBlank String orderId, @NotBlank String warehouse, @NotEmpty List<@Valid Line> lines)
    implements Command<Void> {

  public record Line(@NotBlank String sku, @Positive int quantity) {}
}
