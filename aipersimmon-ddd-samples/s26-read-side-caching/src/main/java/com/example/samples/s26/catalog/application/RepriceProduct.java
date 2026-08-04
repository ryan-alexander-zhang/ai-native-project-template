package com.example.samples.s26.catalog.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** Change a product's price — the field whose staleness a customer notices. */
public record RepriceProduct(@NotBlank String sku, @Positive long priceCents)
    implements Command<Void> {}
