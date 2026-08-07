package com.aipersimmon.ddd.archunit.fixture.good.ordering.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.Externalized;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * A published event that is also routed to a broker: an {@link IntegrationEvent} carrying {@link
 * Externalized @Externalized} with a real destination. The good path of {@code
 * externalizedShouldOnlyAnnotateIntegrationEvents}.
 */
@EventType(name = "com.example.ordering.StockDepleted", version = 1)
@Externalized("ordering.events")
public record GoodStockDepleted(String sku) implements IntegrationEvent {}
