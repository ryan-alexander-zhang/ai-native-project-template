package com.example.samples.s26.catalog.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;

/** Rename a product. The write whose cached copy must not survive it. */
public record RenameProduct(@NotBlank String sku, @NotBlank String name) implements Command<Void> {}
