package com.example.samples.s26.catalog.domain;

import java.util.Optional;

/**
 * The product aggregate's port — the write path, and the one repository in this sample that no cache
 * may decorate.
 *
 * <p>That prohibition is checked twice, in two different ways, because it is the single most tempting
 * mistake in the scenario. {@code ArchitectureTest} forbids anything implementing this interface from
 * touching Redis at all, which catches it at build time. {@code AggregateCacheTrapTest} then wires a
 * caching decorator over it anyway, in test scope, and measures what breaks — because a rule nobody can
 * see the consequences of is a rule somebody will delete.
 */
public interface Products {

  Optional<Product> find(Sku sku);

  void save(Product product);
}
