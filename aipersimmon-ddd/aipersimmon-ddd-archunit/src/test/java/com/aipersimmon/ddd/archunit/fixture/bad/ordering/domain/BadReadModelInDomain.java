package com.aipersimmon.ddd.archunit.fixture.bad.ordering.domain;

import com.aipersimmon.ddd.cqrs.ReadModel;

/**
 * Violates {@code readModelsShouldResideInApplicationOrApi}: a projection declared in the domain
 * layer, where it presents a query's answer as if it were part of the model.
 *
 * <p>It holds no aggregate, so it passes the other half of {@code
 * readModelsShouldBeProjectionShapes} and fails only on placement.
 */
@ReadModel
public record BadReadModelInDomain(String orderId, long totalMinor) {}
