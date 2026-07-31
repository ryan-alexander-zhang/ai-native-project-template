package com.aipersimmon.ddd.archunit.fixture.validation.good;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A command whose every reference-typed component declares its contract: {@code orderId} is
 * required, {@code note} is deliberately optional — {@code @Size} accepts null, so optionality is
 * declared rather than left as an unannotated question mark. The primitive {@code quantity} needs
 * no annotation to be non-null.
 */
public record GoodValidatedCommand(
    @NotBlank String orderId, @Size(max = 200) String note, int quantity)
    implements Command<Void> {}
