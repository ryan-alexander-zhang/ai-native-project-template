package com.example.samples.s12.ordering.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;

/**
 * The catalogue says this product is now called that.
 *
 * <p>A command rather than a direct write, so the inbound adapter has nothing to decide and this arrives
 * through the same transactional channel as everything else — the replica update and the projection rows it
 * invalidates commit together or not at all. Half of that pair applied would leave a replica saying one
 * thing and a list page saying another, with nothing to notice it.
 */
public record RecordProductName(@NotBlank String sku, @NotBlank String name)
    implements Command<Integer> {}
