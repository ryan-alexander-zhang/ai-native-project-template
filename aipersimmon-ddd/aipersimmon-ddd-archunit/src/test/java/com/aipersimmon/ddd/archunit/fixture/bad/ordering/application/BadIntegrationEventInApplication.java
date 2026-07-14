package com.aipersimmon.ddd.archunit.fixture.bad.ordering.application;

import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * Violates {@code integrationEventsShouldResideInApi}: an {@link IntegrationEvent} declared
 * in the application layer instead of the context's {@code ..api..} published contract.
 */
public class BadIntegrationEventInApplication implements IntegrationEvent {
}
