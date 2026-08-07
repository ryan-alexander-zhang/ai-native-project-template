package com.aipersimmon.ddd.archunit.fixture.bad.ordering.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.Externalized;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * Violates the second half of {@code externalizedShouldOnlyAnnotateIntegrationEvents}: a real
 * integration event that declares externalization and names no destination.
 */
@EventType(name = "com.example.ordering.BlankTarget", version = 1)
@Externalized("")
public record BadBlankExternalizedTarget(String orderId) implements IntegrationEvent {}
