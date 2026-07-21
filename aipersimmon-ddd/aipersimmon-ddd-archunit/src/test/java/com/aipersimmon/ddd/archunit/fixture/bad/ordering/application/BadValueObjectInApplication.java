package com.aipersimmon.ddd.archunit.fixture.bad.ordering.application;

import com.aipersimmon.ddd.core.annotation.ValueObject;

/**
 * Violates {@code domainBuildingBlocksShouldResideInDomain}: a {@code @ValueObject} declared in the
 * application layer instead of the domain.
 */
@ValueObject
public record BadValueObjectInApplication(long amountMinor, String currency) {}
