package com.example.samples.s26.catalog.domain;

import com.aipersimmon.ddd.core.event.DomainEvent;

/**
 * A product's name changed.
 *
 * <p>It carries the new name even though the invalidation that listens for it only needs the sku,
 * because the event is the domain's statement of what happened and not a message addressed to one
 * listener. An eviction reads the sku; a listener that wanted to warm the entry rather than drop it
 * would need the name; neither should have to go back to the database to learn what the aggregate just
 * decided.
 *
 * <p>It stays a domain event — LOCAL, in-process, no outbox. Nothing outside this deployable subscribes
 * to it in this sample, and inventing a topic for a cache eviction would be S4's subject dragged in for
 * no reason. What it does mean is that <strong>the eviction is only in this process</strong>: another
 * instance of this service would never hear it. §5 of the companion document is about what that costs
 * and why a shared cache is what makes it survivable.
 */
public record ProductRenamed(Sku sku, String name) implements DomainEvent {}
