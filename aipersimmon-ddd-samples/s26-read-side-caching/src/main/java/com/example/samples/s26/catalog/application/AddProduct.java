package com.example.samples.s26.catalog.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** Put a product in the catalogue. */
public record AddProduct(@NotBlank String sku, @NotBlank String name, @Positive long priceCents)
    implements Command<Void> {}
