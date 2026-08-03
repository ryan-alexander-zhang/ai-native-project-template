package com.example.samples.s21.ordering.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * Place an order.
 *
 * <p>{@code warehouseCode} is the field v3 of the published contract added. It is a command field
 * first and a contract field second: the reason the contract could grow is that this service actually
 * learned something new, and the order of those two events is not reversible.
 */
public record PlaceOrder(
    @NotBlank String customerId, @NotEmpty List<@Valid Line> lines, @NotBlank String warehouseCode)
    implements Command<String> {

  public record Line(@NotBlank String sku, @Positive int quantity) {}
}
