package com.aipersimmon.ddd.archunit.fixture.good.ordering.process;

import com.aipersimmon.ddd.processmanager.definition.HasStep;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;

/** The business state {@link GoodFulfilmentDefinition} reads and returns. */
public record GoodFulfilmentState(String orderId, ProcessStep processStep) implements HasStep {}
