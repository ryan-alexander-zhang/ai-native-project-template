/**
 * The Order aggregate: its root {@link com.example.ordering.domain.order.Order},
 * the internal {@code OrderLine} entity (package-private, reachable only through
 * the root), value objects, domain events, and the {@code Orders} repository
 * port. The order references its customer by identity
 * ({@link com.example.ordering.domain.customer.CustomerId}), never by holding the
 * customer instance.
 */
package com.example.ordering.domain.order;
