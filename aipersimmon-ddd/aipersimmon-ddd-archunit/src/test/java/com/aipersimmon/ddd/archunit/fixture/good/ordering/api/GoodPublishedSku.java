package com.aipersimmon.ddd.archunit.fixture.good.ordering.api;

import com.aipersimmon.ddd.core.annotation.ValueObject;

/**
 * A deliberately published value object: the identifier this context exposes for others to hold, so
 * it lives in {@code ..api..} with the rest of the outward contract rather than in the domain. It
 * keeps its {@code @ValueObject} marker — which is the point, because that marker is also what
 * subjects it to {@code valueObjectsShouldBeImmutable}, and a published type is the last one that
 * should lose an immutability check.
 */
@ValueObject
public record GoodPublishedSku(String value) {}
