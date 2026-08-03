package com.example.samples.s04.inventory.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * Reserve stock for an order.
 *
 * <p>It is a command of <em>this</em> context, in this context's language — not the inbound event
 * wearing a different hat. The inbound adapter translates one into the other, which is what keeps a
 * change to the ordering context's contract from reaching this service's use case.
 *
 * <p>It carries no message id and no source. An earlier version of this sample did, so the handler
 * could consult the inbox itself — and that was wrong: the framework's consumer bridge already
 * consults the inbox with {@code (ce_source, ce_id)} before it publishes the event locally
 * ({@code KafkaIntegrationEventListener:152}). Checking again downstream always sees the bridge's own
 * record, so the handler skipped every single message and the stock was never touched. Nothing
 * failed, nothing logged — the effect was simply absent.
 */
public record ReserveStock(@NotBlank String orderId, @NotEmpty List<@Valid Line> lines)
    implements Command<Void> {

  public record Line(@NotBlank String sku, @Positive int quantity) {}
}
