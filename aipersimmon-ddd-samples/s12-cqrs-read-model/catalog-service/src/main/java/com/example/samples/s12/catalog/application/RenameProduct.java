package com.example.samples.s12.catalog.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;

/** Rename a product. */
public record RenameProduct(@NotBlank String sku, @NotBlank String name) implements Command<Void> {}
