package com.example.samples.s26.catalog.domain;

import com.aipersimmon.ddd.core.event.DomainEvent;

/**
 * A product's price changed.
 *
 * <p>A second event type that invalidates the same key, which is the point of having it: the eviction
 * is keyed on the aggregate, not on the attribute, so a listener per event type would evict the same
 * entry twice and a listener per attribute would be a list that has to be maintained every time the
 * read model grows a field. {@code ProductCacheInvalidation} subscribes to both and does one thing.
 */
public record ProductRepriced(Sku sku, long priceCents) implements DomainEvent {}
