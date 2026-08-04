/**
 * The ordering model, which has never heard of coupons.
 *
 * <p>It is handed a code and a discount as values and records both. The rule that makes that a boundary rather than a
 * convention is {@code ArchitectureTest.nodomainKnowsAnotherContextExists} — stricter than the library's isolation rule,
 * which would allow this package to depend on {@code coupons.api}.
 */
package com.example.samples.s24.ordering.domain;
