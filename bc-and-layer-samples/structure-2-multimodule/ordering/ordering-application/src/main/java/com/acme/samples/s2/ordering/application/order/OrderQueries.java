package com.acme.samples.s2.ordering.application.order;

import java.util.Optional;

/**
 * Read-model port (CQRS-lite, analysis-00005 §5). Queries a projection view
 * directly, bypassing the {@link com.acme.samples.s2.ordering.domain.order.Order}
 * aggregate and its write repository {@code Orders}. Basis: ddd-by-examples/library
 * read models, ardalis CleanArchitecture query services.
 */
public interface OrderQueries {
    Optional<OrderSnapshot> byId(String id);
}
