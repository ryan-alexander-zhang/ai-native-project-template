package com.aipersimmon.ddd.archunit.fixture.bad.ordering.application;

import com.aipersimmon.ddd.cqrs.Command;

/** Command used by {@link BadStagedDispatchHandler} to demonstrate the sendAs violation. */
public record BadStagedDispatch(String orderId) implements Command<Void> {
}
