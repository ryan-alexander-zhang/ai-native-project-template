package com.aipersimmon.ddd.archunit.fixture.good.ordering.application;

import com.aipersimmon.ddd.cqrs.Query;

/** A query the good query handler answers; returns the order id. */
public record GoodFindOrder(String orderId) implements Query<String> {}
