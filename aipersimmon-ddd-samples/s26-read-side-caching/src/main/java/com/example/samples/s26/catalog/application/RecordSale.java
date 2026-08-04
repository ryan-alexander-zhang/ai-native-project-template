package com.example.samples.s26.catalog.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * A sale happened.
 *
 * <p>The high-frequency write, and therefore the one that decides whether the cache is worth having. If
 * this command evicted the product's detail entry, a popular product would be evicted on every sale and
 * its cache entry would never be read twice — all of the cost of caching and none of the benefit.
 * <strong>What makes a value cacheable is how often it is written, not how often it is read.</strong>
 */
public record RecordSale(@NotBlank String sku, @Positive int quantity) implements Command<Void> {}
