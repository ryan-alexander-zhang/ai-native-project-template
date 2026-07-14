package com.aipersimmon.ddd.archunit.fixture.good.ordering.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;

/**
 * A well-formed value object: annotated {@code @ValueObject}, placed in the domain
 * layer, and immutable (a record has only final fields). Exercises the good path of
 * {@code domainBuildingBlocksShouldResideInDomain} and {@code valueObjectsShouldBeImmutable}.
 */
@ValueObject
public record GoodMoney(long amountMinor, String currency) {
}
