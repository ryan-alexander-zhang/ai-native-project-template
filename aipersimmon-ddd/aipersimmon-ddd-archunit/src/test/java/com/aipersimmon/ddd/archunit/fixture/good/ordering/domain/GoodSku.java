package com.aipersimmon.ddd.archunit.fixture.good.ordering.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.aipersimmon.ddd.core.model.Identifier;

/** {@link GoodStockItem}'s identity — a dedicated value object, as the aggregate base requires. */
@ValueObject
public record GoodSku(String value) implements Identifier {}
