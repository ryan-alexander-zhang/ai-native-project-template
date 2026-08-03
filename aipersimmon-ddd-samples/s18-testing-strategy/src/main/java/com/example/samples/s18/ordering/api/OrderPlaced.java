package com.example.samples.s18.ordering.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * The cross-context announcement of the same occurrence.
 *
 * <p>It lives in {@code ..api..} because an integration event is a published contract, not an
 * internal: {@code EventRules.integrationEventsShouldResideInApi()} enforces that, and adopting the
 * rule is what moved this file here.
 *
 * <p>{@code @EventType} is required, and the library's test double is where a missing one shows up
 * first: {@code RecordingIntegrationEvents} builds a real envelope, so it fails rather than recording
 * an event nobody could route. An ArchUnit rule catches it too.
 */
@EventType(name = "s18.ordering.order-placed", version = 1)
public record OrderPlaced(String orderId, String customerId, long amountCents)
    implements IntegrationEvent {}
