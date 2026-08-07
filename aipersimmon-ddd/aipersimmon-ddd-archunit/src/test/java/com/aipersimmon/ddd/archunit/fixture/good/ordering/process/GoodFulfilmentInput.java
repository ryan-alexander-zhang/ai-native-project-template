package com.aipersimmon.ddd.archunit.fixture.good.ordering.process;

import com.aipersimmon.ddd.processmanager.definition.ProcessInput;

/** The input that starts {@link GoodFulfilmentDefinition}. */
public record GoodFulfilmentInput(String orderId) implements ProcessInput {}
