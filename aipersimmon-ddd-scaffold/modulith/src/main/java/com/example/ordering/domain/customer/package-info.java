/**
 * The Customer aggregate: its root
 * {@link com.example.ordering.domain.customer.Customer}, its identity, the
 * {@code Customers} repository port, and the credit rule an order must satisfy.
 * Other aggregates reference a customer by
 * {@link com.example.ordering.domain.customer.CustomerId}.
 */
package com.example.ordering.domain.customer;
